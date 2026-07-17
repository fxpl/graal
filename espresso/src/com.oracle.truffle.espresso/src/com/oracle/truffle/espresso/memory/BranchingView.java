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
    private long counter;                                  // this thread's commit counter (Timestamp)
    private Commit head;                                   // tip of this thread's branch (null = none)
    private final VectorClock clock = new VectorClock();   // this view's happens-before frontier
    private final Map<VersionedLocation, Object> staging = new HashMap<>();
    private final Map<VersionedLocation, Commit> lastWriter = new HashMap<>();  // eager committed cache
    private String pendingConflict;

    public BranchingView(long threadId) {
        this.threadId = threadId;
    }

    Commit head() {
        return head;
    }

    /** This view's happens-before frontier, snapshotted (copied) when published on a lock/terminal tip. */
    VectorClock clock() {
        return clock;
    }

    boolean stagingEmpty() {
        return staging.isEmpty();
    }

    void stage(VersionedLocation loc, Object value) {
        staging.put(loc, value);
    }

    /** Staged value, else the committed value (materialised in lastWriter), else ABSENT (read heap). */
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
        if (staging.isEmpty()) {
            return;
        }
        Commit c = new Commit(new Timestamp(threadId, ++counter), new HashMap<>(staging),
                        head == null ? List.of() : List.of(head), false);
        for (VersionedLocation loc : c.changes().keySet()) {
            lastWriter.put(loc, c);
        }
        staging.clear();
        head = c;
        clock.observe(threadId, counter);   // my frontier now reaches my own new commit
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
    void adopt(Commit forkPoint, Map<VersionedLocation, Commit> forkLastWriter, VectorClock forkClock) {
        this.head = forkPoint;
        this.lastWriter.clear();
        this.lastWriter.putAll(forkLastWriter);
        this.clock.join(forkClock);
    }

    /**
     * Merge the branch tip {@code otherHead} (with its frontier {@code otherClock}) into this branch
     * (eager), the happens-before-race way — no lowest common ancestor. Returns a location written
     * concurrently on both branches (a conflict), else {@code null}. Advances {@code head} to a merge
     * commit and joins the clocks. Assumes staging is empty (callers commit first, as the protocol does
     * at every sync action).
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
        if (head == null) {
            // Fast-forward: no commits of my own, so adopt the other branch wholesale (empty bound ->
            // the walk collects its entire history). No conflict possible: I have written nothing.
            head = otherHead;
            writesNotIn(otherHead, new VectorClock()).forEach(lastWriter::put);
            clock.join(otherClock);
            return null;
        }
        // Walk only the other branch's commits I have not seen (pruned by my clock), newest writer per
        // location. For each, a conflict iff the other side never saw my current writer of that location.
        Map<VersionedLocation, Commit> theirs = writesNotIn(otherHead, clock);
        VersionedLocation conflict = null;
        for (Map.Entry<VersionedLocation, Commit> e : theirs.entrySet()) {
            Commit mine = lastWriter.get(e.getKey());
            if (mine != null && !otherClock.contains(mine.id())) {
                conflict = e.getKey();
                break;
            }
        }
        head = new Commit(new Timestamp(threadId, ++counter), Map.of(), List.of(head, otherHead), conflict != null);
        clock.observe(threadId, counter);
        clock.join(otherClock);
        if (conflict != null) {
            return conflict;
        }
        theirs.forEach(lastWriter::put);   // clean merge: adopt the other branch's writes
        return null;
    }

    /**
     * The newest writer of each location on {@code tip}'s branch that {@code bound} has <em>not</em>
     * already observed. Prunes the DFS at any commit {@code bound} contains — by counter monotonicity
     * that commit and all its ancestry are already in {@code bound} — so this visits only the commits
     * by which {@code tip} diverges from {@code bound}, never the whole DAG.
     */
    private static Map<VersionedLocation, Commit> writesNotIn(Commit tip, VectorClock bound) {
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
            for (VersionedLocation loc : c.changes().keySet()) {
                out.putIfAbsent(loc, c);   // first (newest, DFS from tip) writer dominates
            }
            for (Commit p : c.parents()) {
                stack.push(p);
            }
        }
        return out;
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
