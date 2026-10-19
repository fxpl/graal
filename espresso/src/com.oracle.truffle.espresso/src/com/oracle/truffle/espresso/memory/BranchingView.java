/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.espresso.memory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * A guest thread's view in the branching model (the prototype's {@code branching::LocalVersionStore},
 * eager variant): a {@code head} into the commit DAG, a {@code staging} map of uncommitted writes, a
 * materialised {@code lastWriter} cache giving O(1) committed reads, and a {@link VectorClock} frontier
 * {@code clock} recording, per thread, how far this view has observed. This is the <b>eager</b> policy
 * — the paper's branching model — so a merge reports a conflict <em>at the merge</em>; reads never
 * conflict.
 *
 * <p>Conflict detection is happens-before race detection (FastTrack / DJIT+): rather than compute the
 * lowest common ancestor and diff both branches (O(DAG)), a merge asks two vector-clock questions —
 * "have I already incorporated the other tip?" and, for each write the other branch made that I have
 * not seen, "did the other branch see <em>my</em> latest writer of that location?" — each an O(1)
 * clock lookup against a per-location epoch. The DAG is retained only as the record (it is walked, but
 * pruned by the clock, so only the divergent commits are visited). See {@code branching-vectorclocks.md}.
 *
 * <p>Not thread-safe, and does not need to be: a view is owned by one guest thread. Commits are
 * immutable {@link Commit} nodes shared across views (safe publication via final fields); the frontier
 * {@code clock} is copied before it is published on a tip.
 */
public final class BranchingView implements MemoryView {

    private final long threadId;
    private final GraphTrace trace;                        // non-null only under --java.MemoryModelGraph
    private long counter;                                  // this thread's commit counter (Timestamp)
    private Commit head;                                   // tip of this thread's branch (null = none)
    private VectorClock clock = new VectorClock();         // this view's happens-before frontier
    private boolean clockShared;                           // clock handed to a tip; copy-on-write next mutation
    private Map<VersionedLocation, Object> staging = new HashMap<>();  // reassigned (moved) on commit
    private final Map<VersionedLocation, Commit> lastWriter = new HashMap<>();  // eager committed cache
    // Sync-as-Share (stage-on-access): per-app-owned-array working copy, materialised on first access and
    // reused for reads/writes/unwrap this epoch. Flushed (touched indices only) to per-index staging and
    // cleared at each commit, so it rebuilds from post-commit committed state next epoch.
    private final Map<StaticObject, ArrayVersion> arrays = new HashMap<>();
    private String pendingConflict;
    // Writes in this view's history: its own commits, plus those its merges brought in, plus the
    // parent's total adopted at start. The difference across an acquire is what that acquire brought in.
    // Schedule explorer only.
    private long historyWrites;
    private int lastAcquiredWrites;   // writes the most recent acquire (monitor enter, thread start) brought in

    public BranchingView(long threadId, GraphTrace trace) {
        this.threadId = threadId;
        this.trace = trace;
    }

    Commit head() {
        return head;
    }

    /**
     * Publish this view's frontier for a lock/terminal tip. Returns the clock <em>by reference</em> and
     * marks it shared, so no copy is allocated on the (hot) lock path; the view copies it lazily the
     * next time it mutates the clock ({@link #ownClock}), so the published instance is never mutated
     * after publication. A read-only lock (which never mutates the clock) therefore allocates nothing.
     */
    VectorClock publishClock() {
        clockShared = true;
        return clock;
    }

    /** The clock, made private to this view first (copy-on-write) so it can be mutated safely. */
    private VectorClock ownClock() {
        if (clockShared) {
            clock = new VectorClock(clock);
            clockShared = false;
        }
        return clock;
    }

    boolean stagingEmpty() {
        return staging.isEmpty();
    }

    int stagingSize() {
        return staging.size();
    }

    void stage(VersionedLocation loc, Object value) {
        staging.put(loc, value);
        VersioningStats.observeStaging(staging.size());
    }

    // --- Sync-as-Share array working copies (stage-on-access) ---

    /** The acting thread's cached working copy of {@code array}, or {@code null} if not yet materialised. */
    ArrayVersion arrayVersion(StaticObject array) {
        return arrays.get(array);
    }

    /** Cache a freshly materialised working copy of {@code array}. */
    void putArrayVersion(StaticObject array, ArrayVersion av) {
        arrays.put(array, av);
    }

    /**
     * Fold each array working copy's touched indices into per-index {@link ArrayLocation} staging, so the
     * commit/merge machinery sees exactly the elements this thread wrote. Reads (untouched copies) flush
     * nothing. Called just before a commit.
     */
    void flushArrays() {
        for (Map.Entry<StaticObject, ArrayVersion> e : arrays.entrySet()) {
            StaticObject array = e.getKey();
            ArrayVersion av = e.getValue();
            for (int i = av.touched.nextSetBit(0); i >= 0; i = av.touched.nextSetBit(i + 1)) {
                stage(new ArrayLocation(array, i), java.lang.reflect.Array.get(av.host, i));
            }
        }
    }

    /** Drop all working copies; they rebuild from committed state on next access. */
    void clearArrays() {
        arrays.clear();
    }

    /** Staged value, else the committed value (materialised in lastWriter), else ABSENT. The shared
     *  genesis (version-0) cache is consulted by the model after this, not here. */
    Object read(VersionedLocation loc) {
        Object staged = staging.get(loc);
        if (staged != null) {
            return staged;
        }
        Commit writer = lastWriter.get(loc);
        return writer != null ? writer.changes().get(loc) : MemoryModel.ABSENT;
    }

    /** Turn staged writes into a new commit on this thread's branch (no-op if nothing staged). */
    void commitStaging() {
        // Fold array working-copy writes into per-index staging, then drop the copies (they rebuild from
        // post-commit committed state next epoch). Done before the empty check so a commit whose only
        // writes are array writes still lands.
        flushArrays();
        clearArrays();
        if (staging.isEmpty()) {
            return;
        }
        // Move the staging map into the commit rather than copying it (new HashMap<>(staging)): the
        // commit takes sole ownership and staging gets a fresh empty map, so changes() is still
        // immutable-after-publication (nothing else aliases it) at no copy cost. A fresh empty map also
        // beats staging.clear() (which would retain the old capacity). The single biggest per-commit
        // allocation, gone.
        Timestamp parent = head == null ? null : head.id();
        Map<VersionedLocation, Object> changes = staging;
        staging = new HashMap<>();
        Commit c = new Commit(new Timestamp(threadId, ++counter), changes,
                        head == null ? List.of() : List.of(head), false);
        VersioningStats.onCommit(changes.size());
        historyWrites += changes.size();
        for (VersionedLocation loc : c.changes().keySet()) {
            lastWriter.put(loc, c);
        }
        head = c;
        ownClock().observe(threadId, counter);   // my frontier now reaches my own new commit
        VersioningStats.observeLastWriter(lastWriter.size());
        if (trace != null) {
            trace.commit(threadId, counter, parent, c.changes());
        }
    }

    /**
     * Snapshot this view's committed cache for handing to a freshly-spawned child (see
     * {@link #adopt}). Taken on the parent thread at spawn time, so it is stable even though the
     * parent keeps running afterwards.
     */
    Map<VersionedLocation, Commit> snapshotLastWriter() {
        return new HashMap<>(lastWriter);
    }

    /**
     * Fork: start this (fresh) view at another branch's tip, committed cache, and frontier clock.
     * Called on the child at start with the snapshot the parent captured at spawn (spawn: child adopts
     * parent).
     */
    void adopt(Commit forkPoint, Map<VersionedLocation, Commit> forkLastWriter, VectorClock forkClock, long forkHistoryWrites) {
        this.head = forkPoint;
        this.lastWriter.clear();
        this.lastWriter.putAll(forkLastWriter);
        ownClock().join(forkClock);
        // A fresh view has an empty history, so everything in the parent's history is brought in here.
        this.historyWrites = forkHistoryWrites;
        this.lastAcquiredWrites = (int) forkHistoryWrites;
    }

    /**
     * Merge the branch tip {@code otherHead} (with its frontier {@code otherClock}) into this branch
     * (eager), the happens-before-race way — no lowest common ancestor. Returns a location written
     * concurrently on both branches (a conflict), else {@code null}. Advances {@code head} to a merge
     * commit and joins the clocks. Staging must be empty — every sync action (lock acquire, join,
     * volatile read/write) commits first, so the conflict rule's {@code lastWriter} check covers the
     * thread's just-committed writes (the paper's {@code dom(δ)} term in Conflict-Free-Merge).
     *
     * <p>The conflict rule, per location the other branch wrote that I have not seen: it is a conflict
     * iff the other branch never saw <em>my</em> latest writer of that location (so the two writers are
     * concurrent); otherwise the other's write is causally later and I adopt it. A writer from the
     * shared prefix is in {@code otherClock}, so it never triggers a conflict.
     */
    VersionedLocation merge(Commit otherHead, VectorClock otherClock) {
        assert staging.isEmpty();
        if (otherHead == null || otherHead == head) {
            return null;
        }
        if (clock.contains(otherHead.id())) {
            // Already incorporated (a lock re-acquire, a re-merge): nothing new. O(1), and it keeps an
            // ordinary monitor-exit's re-publish from spawning spurious merge commits.
            return null;
        }
        if (head == null || otherClock.contains(head.id())) {
            // Fast-forward: my whole branch is contained in the other (or I have no commits), so I have
            // no divergent writes -> adopt the other tip wholesale. No conflict is possible and no merge
            // commit is created (git's fast-forward). This is the steady state of a contended lock: the
            // previous holders already merged my last commit, so on re-acquire I am strictly behind, and
            // the DAG stays a linear chain instead of growing a merge node per acquisition.
            VersioningStats.onMerge(true);
            writesNotIn(otherHead, clock, this).forEach(lastWriter::put);
            if (trace != null) {
                trace.fastForward(threadId, head == null ? null : head.id(), otherHead.id());
            }
            head = otherHead;
            ownClock().join(otherClock);
            VersioningStats.observeLastWriter(lastWriter.size());
            return null;
        }
        VersioningStats.onMerge(false);
        // Walk only the other branch's commits I have not seen (pruned by my clock), newest writer per
        // location. For each, a conflict iff the other side never saw my current writer of that location.
        Map<VersionedLocation, Commit> theirs = writesNotIn(otherHead, clock, this);
        VersionedLocation conflict = null;
        for (Map.Entry<VersionedLocation, Commit> e : theirs.entrySet()) {
            Commit mine = lastWriter.get(e.getKey());
            if (mine != null && !otherClock.contains(mine.id())) {
                conflict = e.getKey();
                break;
            }
        }
        Commit myHead = head;
        head = new Commit(new Timestamp(threadId, ++counter), Map.of(), List.of(myHead, otherHead), conflict != null);
        VersioningStats.onCommit(0);   // merge commit carries no changes of its own
        ownClock().observe(threadId, counter);
        clock.join(otherClock);
        if (trace != null) {
            trace.merge(threadId, counter, myHead == null ? null : myHead.id(), otherHead.id(), conflict != null);
        }
        if (conflict != null) {
            return conflict;
        }
        theirs.forEach(lastWriter::put);   // clean merge: adopt the other branch's writes
        VersioningStats.observeLastWriter(lastWriter.size());
        return null;
    }

    /**
     * The newest writer of each location on {@code tip}'s branch that {@code bound} has <em>not</em>
     * already observed. Prunes the DFS at any commit {@code bound} contains — by counter monotonicity
     * that commit and all its ancestry are already in {@code bound} — so this visits only the commits
     * by which {@code tip} diverges from {@code bound}, never the whole DAG. Adds the visited commits'
     * write counts to {@code into}'s {@link #historyWrites}.
     */
    private static Map<VersionedLocation, Commit> writesNotIn(Commit tip, VectorClock bound, BranchingView into) {
        Map<VersionedLocation, Commit> out = new HashMap<>();
        Set<Commit> visited = new HashSet<>();
        Deque<Commit> stack = new ArrayDeque<>();
        if (tip != null) {
            stack.push(tip);
        }
        while (!stack.isEmpty()) {
            Commit c = stack.pop();
            if (c == null || !visited.add(c)) {
                continue;
            }
            if (bound.contains(c.id())) {
                continue;   // bound already has c (and, by monotonicity, all of c's ancestry) -> prune
            }
            into.historyWrites += c.changes().size();
            for (VersionedLocation loc : c.changes().keySet()) {
                out.putIfAbsent(loc, c);   // first (newest, DFS from tip) writer dominates
            }
            for (Commit p : c.parents()) {
                stack.push(p);
            }
        }
        return out;
    }

    /** Writes in this view's history; the difference across an acquire is what it brought in. */
    long historyWrites() {
        return historyWrites;
    }

    int lastAcquiredWrites() {
        return lastAcquiredWrites;
    }

    void setLastAcquiredWrites(int n) {
        this.lastAcquiredWrites = n;
    }

    // --- MemoryView: deferred-conflict flag (raised at the bytecode safe point / thread end) ---

    void setPendingConflict(String description) {
        this.pendingConflict = description;
    }

    @Override
    public boolean hasPendingConflict() {
        return pendingConflict != null;
    }

    @Override
    public String takePendingConflict() {
        String c = pendingConflict;
        pendingConflict = null;
        return c;
    }
}
