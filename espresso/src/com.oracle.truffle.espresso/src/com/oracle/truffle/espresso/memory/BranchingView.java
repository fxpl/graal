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

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.oracle.truffle.espresso.impl.Field;
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
    private Map<FieldLocation, Object> staging = new HashMap<>(); // staged changes which are cleared when committed to history
    private final Map<ObjectNumber, WorkingArray> stagedArrays = new HashMap<>(); // versioned arrays this view has accessed, as this view sees them

    private final ReadCache readCache = new ReadCache();

    private String pendingConflict;

    // A count shared between views for the number of objects purged,
    // and the number we saw at the last purge
    private final AtomicLong deadObjectCount;
    private long deadSetSizeAtLastPurge;

    // objects with locations in staging or the read cache, so their entries can be dropped when they die
    private final Map<ObjectNumber, StagedReference> trackedObjects = new HashMap<>();
    private final ReferenceQueue<StaticObject> deadQueue = new ReferenceQueue<>();

    // commits visited by the last read walk, to pre-size the next walk's visited set
    private int lastWalkSize = 16;

    // commits in the last history collected by merge, to pre-size the next merge's history sets
    private int lastHistorySize = 16;

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

    // We reduce the staging size by removing references to static
    // objects that have been GC'd.
    // The memory model cleaner will manage setting that the object is dead
    // and incrementing the dead count, as that is view independent
    // and affects all threads and the entire DAG
    private void emptyDeadQueue() {
        Reference<?> deadReference;
        while ((deadReference = deadQueue.poll()) != null) {
            StagedReference deadObject = (StagedReference) deadReference;
            trackedObjects.remove(deadObject.number);
            stagedArrays.remove(deadObject.number);
            deadObject.locations.forEach(k -> staging.remove(k));
            readCache.evictAll(deadObject.locations);
        }
    }

    // Track a holder is in the staging or the read cache so that it can
    // be dropped from both when the holder dies
    private StagedReference track(StaticObject holder) {
        return trackedObjects.computeIfAbsent(holder.getObjectNumber(), n -> new StagedReference(holder, n, deadQueue));
    }

    // Record that loc, associated with a field loc, is in staging or the read cache, so it is
    // dropped from both when holder dies
    private void track(StaticObject holder, FieldLocation loc) {
        track(holder).locations.add(loc);
    }

    void stageField(StaticObject holder, Field field, Object value) {
        FieldLocation loc = new FieldLocation(holder.getObjectNumber(), field);

        if (staging.put(loc, value) == null) {
            // This is the first time we have staged a write to this
            // object's field this commit window
            track(holder, loc);
        }

        emptyDeadQueue();
        VersioningStats.observeStaging(staging.size());
    }

    /** Staged value, else the committed value, else ABSENT. The shared genesis (version-0) cache is
     *  consulted by the model after this, not here. */
    Object readField(StaticObject holder, Field field) {
        FieldLocation loc = new FieldLocation(holder.getObjectNumber(), field);
        Object known = stagedOrCached(loc);
        if (known != null) {
            return known;
        }

        if (state().commit == null) {
            return MemoryModel.ABSENT;
        }

        Object value = getLastCommittedValue(loc);
        readCache.put(loc, value);
        track(holder, loc);
        return value;
    }

    // The staged value, else the cached one (possibly ABSENT), else null
    private Object stagedOrCached(FieldLocation loc) {
        Object staged = staging.get(loc);
        if (staged != null) {
            return staged;
        }
        return readCache.get(loc);
    }

    /** The newest committed value of {@code loc} under the head, else ABSENT. The head must be set. */
    private Object getLastCommittedValue(FieldLocation loc) {
        // The pathological cases are:
        // 1) the value is all the way back at version 0
        // 2) we have multiple candidates

        // Walk back from the current head through the commit history to find
        // the most recent committed value for this location.
        // We know that there is a unique last writer for each location in the
        // commit history as the merges are conflict free.
        // We have to make sure to find the latest committed value for this location.

        Set<Commit> visited = HashSet.newHashSet(lastWalkSize);
        Set<Commit> candidates = new HashSet<>();
        Deque<Commit> heads = new ArrayDeque<>();

        visited.add(state().commit);
        heads.add(state().commit);

        while (!heads.isEmpty()) {
            Commit current = heads.pop();

            // once we find a candidate stop
            if (current.fieldChanges().containsKey(loc)) {
                candidates.add(current);
            } else {
                // add the parents to explore if we haven't already
                // seen them
                for (Commit p : current.parents()) {
                    if (visited.add(p)) {
                        heads.add(p);
                    }
                }
            }
        }

        lastWalkSize = visited.size();
        VersioningStats.onReadWalk(visited.size(), !candidates.isEmpty());

        // If no candidates were found, the location is absent.
        if (candidates.isEmpty()) {
            return MemoryModel.ABSENT;
        }

        // There was only one candidate commit, so just return that
        if (candidates.size() == 1) {
            return candidates.iterator().next().fieldChanges().get(loc);
        }

        // We had multiple candidates, so now we need to work out which is the only
        // one which isn't shadowed
        Set<Commit> alive = new HashSet<>(candidates);
        visited.clear();

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
        return latest.fieldChanges().get(loc);
    }

    /** Turn staged writes into a new commit on this thread's branch (no-op if nothing staged). */
    private void commitStaging() {
        // before merging the changes into the head, first try to
        // purge any dead objects from the head commit (non-recursive)
        long deadCount = deadObjectCount.get();
        if (state().commit != null && deadCount > deadSetSizeAtLastPurge) {
            state().commit.purgeObjects();
            deadSetSizeAtLastPurge = deadCount;
        }

        // before commit, try to clear out anything that is dead from staging
        emptyDeadQueue();

        // Create the array changes set and clear all the written bit from the staged array
        Map<ObjectNumber, ArrayChanges> arrayChanges = new HashMap<>();
        stagedArrays.forEach((number, w) -> {
            if (!w.written.isEmpty()) {
                arrayChanges.put(number, ArrayChanges.of(w));   // copies the bits and values
                w.written.clear();
            }
        });

        if (staging.isEmpty() && arrayChanges.isEmpty()) {
            return;
        }

        // Whatever we wrote is going to be the most up-to-date value
        // So avoid any walks looking for it
        readCache.putAll(staging);

        // Grab the staging and clear it
        Map<FieldLocation, Object> fieldChanges = staging;
        staging = new HashMap<>();

        VersioningStats.onCommit(fieldChanges.size(), arrayChanges.size(),
                        arrayChanges.values().stream().mapToLong(c -> c.written().cardinality()).sum());
        setHead(state().commit == null ? Commit.first(fieldChanges, arrayChanges) : state().commit.extend(fieldChanges, arrayChanges));
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
    Conflicts merge(Commit otherHead) {
        assert staging.isEmpty();

        if (state().commit == null) {
            // either pickup the other commit as head (there is nothing to merge)
            // or the genuinely is no operation to do here
            if (otherHead != null) {
                setHead(otherHead);
                // our array copies were built from version 0 alone, so may be stale against the
                // adopted history; with no head we have never committed, so none has written bits
                stagedArrays.clear();
            }
            return Conflicts.NONE;
        }

        if (otherHead == null) {
            // just return as there is nothing to merge
            return Conflicts.NONE;
        }

        if (otherHead == state().commit) {
            // no point in doing this
            return Conflicts.NONE;
        }

        // walk back from head and otherHead and collect their history
        Set<Commit> headsHistory = HashSet.newHashSet(lastHistorySize);
        Deque<Commit> heads = new ArrayDeque<>();

        headsHistory.add(state().commit);
        heads.add(state().commit);

        while(!heads.isEmpty()) {
            Commit current = heads.pop();
            for (Commit p : current.parents()) {
                if (headsHistory.add(p)) {
                    heads.add(p);
                }
            }
        }

        if (headsHistory.contains(otherHead)) {
            // their head is already in our history, so nothing to merge
            return Conflicts.NONE;
        }

        Set<Commit> otherHeadsHistory = HashSet.newHashSet(lastHistorySize);

        otherHeadsHistory.add(otherHead);
        heads.add(otherHead);

        while(!heads.isEmpty()) {
            Commit current = heads.pop();
            for (Commit p : current.parents()) {
                if (otherHeadsHistory.add(p)) {
                    heads.add(p);
                }
            }
        }

        lastHistorySize = Math.max(headsHistory.size(), otherHeadsHistory.size());

        Set<Commit> mineOnly = new HashSet<>(headsHistory);
        mineOnly.removeAll(otherHeadsHistory);

        Set<FieldLocation> mineFieldsWritten = new HashSet<>();
        Map<ObjectNumber, BitSet> mineArrayWritten = new HashMap<>();
        for (Commit c : mineOnly) {
            mineFieldsWritten.addAll(c.fieldChanges().keySet());
            c.arrayChanges().forEach((number, changes) -> {
                mineArrayWritten.computeIfAbsent(number, n -> new BitSet()).or(changes.written);
            });
        }

        Set<Commit> theirsOnly = new HashSet<>(otherHeadsHistory);
        theirsOnly.removeAll(headsHistory);
        Set<FieldLocation> fieldConflicts = new HashSet<>();
        Map<ObjectNumber, BitSet> arrayConflicts = new HashMap<>();
        for (Commit c : theirsOnly) {
            for (FieldLocation loc : c.fieldChanges().keySet()) {
                // the merge brought in an updated value of a location
                // so drop the cached value
                readCache.evict(loc);
                if (mineFieldsWritten.contains(loc)) {
                    fieldConflicts.add(loc);
                }
            }

            // We clear out only the arrays that were changed by theirs
            // we could instead change just the elements and keep what
            // didn't change but it's a bit more work
            c.arrayChanges().forEach((o, changes) -> {
                stagedArrays.remove(o);
                BitSet mine = mineArrayWritten.get(o);
                if (mine != null && mine.intersects(changes.written)) {
                    BitSet both = (BitSet) mine.clone();
                    both.and(changes.written);
                    arrayConflicts.computeIfAbsent(o, n -> new BitSet()).or(both);
                }
            });
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
            return Conflicts.NONE;
        }

        if (!fieldConflicts.isEmpty() || !arrayConflicts.isEmpty()) {
            return new Conflicts(fieldConflicts, arrayConflicts);
        }

        // create the merge of the parents
        Commit mergeCommit = Commit.merge(state().commit, otherHead);
        VersioningStats.onMerge();
        setHead(mergeCommit);
        return Conflicts.NONE;
    }

    // pull from another commit and report any conflicting locations
    Conflicts pull(SyncState from) {
        commitStaging();
        return merge(from.commit);
    }

    void commit() {
        commitStaging();

        // after commit, try to compress the DAG
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

    // Walk back to the root and find the ordered writes to the array reachable
    // from the current head, and then apply those writes.
    // We do this more general walk, instead of the stop at a first commit for a field, because arrays require
    // writes to all of their indexes, so figuring out which array commits shadow which others is more complicated
    // than collecting history and applying it
    private WorkingArray createArrayFromHistory(StaticObject array) {
        ObjectNumber number = array.getObjectNumber();
        Object storage = array.copyOfRawArray(array.getKlass().getLanguage());

        // We have no history of changes yet, so we must be at version 0,
        // so just return the copy
        if (state().commit == null) {
            return new WorkingArray(storage);
        }

        Set<Commit> order = LinkedHashSet.newLinkedHashSet(lastWalkSize);  // ancestors before descendants
        Set<Commit> visited = HashSet.newHashSet(lastWalkSize);
        Deque<Commit> heads = new ArrayDeque<>();
        heads.push(state().commit);

        while (!heads.isEmpty()) {
            Commit c = heads.peek();

            if (order.contains(c)) {
                // If we have already ordered this thing in history just skip over it
                heads.pop();
            } else if (visited.add(c)) {
                // Otherwise it is the first time we have seen this node, so push its parents to the front of the
                // stack, leaving this child where it is so we revisit it later once the parents have been
                // ordered
                for (Commit p : c.parents()) {
                    if (!order.contains(p)) {
                        heads.push(p);
                    }
                }
            } else {
                // Now we know where the child lives in the order of things so add it to the ordered order set
                heads.pop();
                order.add(c);
            }
        }
        lastWalkSize = order.size();

        // Apply each commit's writes in that order, so the last writer of each index wins.
        for (Commit c : order) {
            ArrayChanges changes = c.arrayChanges().get(number);
            if (changes != null) {
                changes.applyTo(storage);
            }
        }

        return new WorkingArray(storage);
    }

    public Object arrayForRead(StaticObject array, int from, int to) {
        WorkingArray working = stagedArrays.get(array.getObjectNumber());
        if (working != null) {
            return working.storage;
        }

        working = createArrayFromHistory(array);
        stagedArrays.put(array.getObjectNumber(), working);
        track(array);
        return working.storage;
    }

    public Object arrayForWrite(StaticObject array, int from, int to) {
        WorkingArray working = stagedArrays.get(array.getObjectNumber());
        if (working == null) {
            working = createArrayFromHistory(array);
            stagedArrays.put(array.getObjectNumber(), working);
            track(array);
        }

        working.markWrittenRange(from, to);
        return working.storage;
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
