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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * A guest thread's view in the branching model (the prototype's {@code branching::LocalVersionStore}):
 * a {@code head} into the commit DAG and a {@code staging} map of uncommitted writes.
 *
 * <p>Not thread-safe, and does not need to be: a view is owned by one guest thread. Commits are
 * immutable {@link Commit} nodes shared across views (safe publication via final fields).
 */
public final class BranchingView implements MemoryView {

    // This thread's branch, shared with its Thread object. Null until first use: the view can be created
    // during VM bootstrap, before the Thread exists. Never read directly, always through state().
    private SyncState unboundOrState;
    private final Supplier<SyncState> bindState;
    private Map<VersionedLocation, Object> staging = new HashMap<>(); // staged changes which are cleared when committed to history

    // Sync-as-Share (stage-on-access): per-app-owned-array working copy, materialised on first access and
    // reused for reads/writes/unwrap this epoch. Flushed (touched indices only) to per-index staging and
    // cleared at each commit, so it rebuilds from post-commit committed state next epoch.
    private final Map<StaticObject, ArrayVersion> arrays = new HashMap<>();
    private String pendingConflict;

    // A set shared between views for objects who are no longer referenceable and
    // should be removed when found
    private final AtomicLong deadObjectCount;
    private long deadSetSizeAtLastPurge;

    public BranchingView(Supplier<SyncState> bindState, AtomicLong deadObjectCount) {
        this.bindState = bindState;
        this.deadObjectCount = deadObjectCount;
    }

    /** This thread's sync state, bound on first use. */
    private SyncState state() {
        SyncState s = unboundOrState;
        if (s == null) {
            s = bindState.get();
            assert s != null : "binding a view produced no sync state";
            unboundOrState = s;
        }
        return s;
    }

    private void setHead(Commit c) {
        // we must increment before decrementing, otherwise in the case that
        // head == c, we would make the refcount 0 briefly which would represent
        // an unreferenced node
        if (c != null) {
            c.incRef();
        }
        if (state().commit != null) {
            state().commit.decRef();
        }
        state().commit = c;
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

    /** Staged value, else the committed value, else ABSENT. The shared genesis (version-0) cache is
     *  consulted by the model after this, not here. */
    Object read(VersionedLocation loc) {
        Object staged = staging.get(loc);
        if (staged != null) {
            return staged;
        }

        if (state().commit == null) {
            return MemoryModel.ABSENT;
        }

        // The pathological cases are:
        // 1) the value is all the way back at version 0
        // 2) we have multiple candidates

        // Walk back from the current head through the commit history to find
        // the most recent committed value for this location.
        // We know that there is a unique last writer for each location in the
        // commit history as the merges are conflict free.
        // We have to make sure to find the latest committed value for this location.

        Set<Commit> visited = new HashSet<>();
        Set<Commit> candidates = new HashSet<>();
        Set<Commit> heads = new HashSet<>();
        heads.add(state().commit);

        while (!heads.isEmpty()) {
            Commit current = heads.iterator().next();
            heads.remove(current);

            if (visited.contains(current)) {
                continue;
            }

            visited.add(current);

            // once we find a candidate stop
            if (current.changes().containsKey(loc)) {
                candidates.add(current);
            } else {
                // add the two parents to explore
                heads.addAll(current.parents());
            }
        }

        // If no candidates were found, the location is absent.
        if (candidates.isEmpty()) {
            return MemoryModel.ABSENT;
        }

        // There was only one candidate commit, so just return that
        if (candidates.size() == 1) {
            return candidates.iterator().next().changes().get(loc);
        }

        // We had multiple candidates, so now we need to work out which is the only
        // one which isn't shadowed
        Set<Commit> alive = new HashSet<>(candidates);
        visited = new HashSet<>();

        for (Commit c : candidates) {

            // If we have already visited this commit then we already know about
            // it and it's ancestry, so continue to the next candidate
            if (!alive.contains(c)) {
                continue;
            }

            // Otherwise, start walking back from c to build up a collection of
            // nodes visible from c. We reuse visited so eventually we will
            // end up with just one commit not visible from the others
            Deque<Commit> stack = new ArrayDeque<>(c.parents());
            while (!stack.isEmpty()) {
                Commit x = stack.pop();

                // already went this way
                if (!visited.add(x)) {
                    continue;
                }

                // x is an ancestor of c so it can't be newest
                alive.remove(x);
                stack.addAll(x.parents());
            }
        }

        // alive now holds exactly one commit: the newest writer
        assert alive.size() == 1;
        Commit latest = alive.iterator().next();
        return latest.changes().get(loc);
    }

    /** Turn staged writes into a new commit on this thread's branch (no-op if nothing staged). */
    private void commitStaging() {
        // Fold array working-copy writes into per-index staging, then drop the copies (they rebuild from
        // post-commit committed state next epoch). Done before the empty check so a commit whose only
        // writes are array writes still lands.
        flushArrays();
        clearArrays();

        // before merging the changes into the head, first try to
        // purge any dead objects from the head commit (non-recursive)
        long deadCount = deadObjectCount.get();
        if (state().commit != null && deadCount > deadSetSizeAtLastPurge) {
            state().commit.purgeObjects();
            deadSetSizeAtLastPurge = deadCount;
        }

        if (staging.isEmpty()) {
            return;
        }

        // Grab the staging and clear it
        Map<VersionedLocation, Object> changes = staging;
        staging = new HashMap<>();
        VersioningStats.onCommit(changes.size());
        setHead(state().commit == null ? Commit.first(changes) : state().commit.extend(changes));
    }

    /** Shallow compression from this thread's head (no-op if it has no head yet). */
    private void compress() {
        if (state().commit != null) {
            state().commit.compressUpwards();
        }
    }

    /**
     * Fork: start this (fresh) view at another branch's tip. Called on the child at start with the tip
     * the parent captured at spawn (spawn: child adopts parent).
     */
    void adopt(Commit forkPoint) {
        setHead(forkPoint);
    }

    /**
     * Merge the branch tip {@code otherHead} into this branch. Returns a set of locations written
     * by both branches (empty means none). Staging must be empty — every sync action (lock
     * acquire, join, volatile read/write) commits first.
     */
    Set<VersionedLocation> merge(Commit otherHead) {
        assert staging.isEmpty();

        if (state().commit == null) {
            // either pickup the other commit as head (there is nothing to merge)
            // or the genuinely is no operation to do here
            if (otherHead != null) {
                setHead(otherHead);
            }
            return Set.of();
        }

        if (otherHead == null) {
            // just return as there is nothing to merge
            return Set.of();
        }

        if (otherHead == state().commit) {
            // no point in doing this
            return Set.of();
        }

        // walk back from head and otherHead and collect their history
        Set<Commit> headsHistory = new HashSet<>();
        Set<Commit> visited = new HashSet<>();
        Set<Commit> heads = new HashSet<>();
        heads.add(state().commit);

        while(!heads.isEmpty()) {
            Commit current = heads.iterator().next();
            heads.remove(current);

            if(visited.contains(current)) {
                continue;
            }
            visited.add(current);

            headsHistory.add(current);
            heads.addAll(current.parents());
        }

        if (headsHistory.contains(otherHead)) {
            // their head is already in our history, so nothing to merge
            return Set.of();
        }

        Set<Commit> otherHeadsHistory = new HashSet<>();
        visited.clear();
        heads.clear();
        heads.add(otherHead);

        while(!heads.isEmpty()) {
            Commit current = heads.iterator().next();
            heads.remove(current);

            if(visited.contains(current)) {
                continue;
            }
            visited.add(current);

            otherHeadsHistory.add(current);
            heads.addAll(current.parents());
        }

        if (otherHeadsHistory.contains(state().commit)) {
            // if this head is already some ancestor of the other head, then
            // there is no point in merging (it would not be wrong it just creates
            // extra edges and makes reference counting compaction harder)
            // so instead just fast-forward the head to the other head.
            // This can happen when we take a lock we just released
            // but have committed nothing in the mean time but the lock has
            // moved on by some other thread
            setHead(otherHead);

            // At this point, we have also moved our head, so try to compress
            // the history as we dropped a ref to part way through the
            // history
            compress();
            return Set.of();
        }

        Set<Commit> mineOnly = new HashSet<>(headsHistory);
        mineOnly.removeAll(otherHeadsHistory);
        Set<Commit> theirsOnly = new HashSet<>(otherHeadsHistory);
        theirsOnly.removeAll(headsHistory);

        Set<VersionedLocation> changedLocations = new HashSet<>();
        for (Commit c : mineOnly) {
            changedLocations.addAll(c.changes().keySet());
        }

        Set<VersionedLocation> conflicts = new HashSet<>();
        for (Commit c : theirsOnly) {
            for (VersionedLocation loc : c.changes().keySet()) {
                if (changedLocations.contains(loc)) {
                    conflicts.add(loc);
                }
            }
        }

        if (!conflicts.isEmpty()) {
            return conflicts;
        }

        // create the merge of the parents
        Commit mergeCommit = Commit.merge(state().commit, otherHead);
        VersioningStats.onMerge();
        setHead(mergeCommit);
        return Set.of();
    }

    // pull from another commit and report any conflicting locations
    Set<VersionedLocation> pull(SyncState from) {
        commitStaging();
        return merge(from.commit);
    }

    void commit() {
        commitStaging();
        compress();
    }

    // commit, compress and push the changes to the 'to' location
    void push(SyncState to) {
        if (to.commit != null) {
            to.commit.decRef();
        }

        commit();

        if (state().commit != null) {
            state().commit.incRef();
        }

        to.commit = state().commit;
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
