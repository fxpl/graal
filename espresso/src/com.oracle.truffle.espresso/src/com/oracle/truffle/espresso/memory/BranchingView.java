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

import java.lang.Integer;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * A guest thread's view in the branching model (the prototype's {@code branching::LocalVersionStore}):
 * a {@code head} into the commit DAG, and working copies of the versioned objects and arrays it has
 * accessed, with the writes it has not yet committed marked.
 *
 * <p>Not thread-safe, and does not need to be: a view is owned by one guest thread. Commits are
 * immutable {@link Commit} nodes shared across views (safe publication via final fields).
 *
 * <p>Finding each side's own commits when merging is the one thing that differs between ways of
 * answering "is this commit in that history?". This class walks both histories in generation order;
 * {@link VectorClockBranchingView} overrides the package-private hooks ({@link #nextCommit},
 * {@link #mergeCommit}, {@link #divergence}, {@link #tookIn}, {@link #pushedTo}) to answer it with
 * vector clocks.
 */
public class BranchingView implements MemoryView {

    // This thread's branch, shared with its Thread object. Null until first use: the view can be created
    // during VM bootstrap, before the Thread exists. Never read directly, always through state().
    private SyncState unboundOrState;
    private final Supplier<SyncState> bindState;
    private final Map<ObjectNumber, WorkingObject> workingObjects = new HashMap<>(); // versioned objects this view has accessed, as this view sees them
    private final Map<ObjectNumber, WorkingObject> writtenObjects = new HashMap<>(); // ...of which written since the last commit
    private final Map<ObjectNumber, WorkingArray> workingArrays = new HashMap<>(); // versioned arrays this view has accessed, as this view sees them
    private final Map<ObjectNumber, WorkingArray> writtenArrays = new HashMap<>(); // ...of which written since the last commit

    private ConflictReport pendingConflict; // found by a synchronisation action, raised at the next safe point

    // Set when the thread starts (or, for the thread that runs main, when versioning is turned on).
    // A read before then would see version 0 regardless of what the thread inherits, so accesses
    // assert it.
    private boolean activated;

    void activate() {
        activated = true;
    }

    // A count shared between views for the number of objects purged,
    // and the number we saw at the last purge
    private final AtomicLong deadObjectCount;
    private long deadSetSizeAtLastPurge;

    // objects in workingObjects or workingArrays, so their entries can be dropped when they die
    private final Map<ObjectNumber, StagedReference> trackedObjects = new HashMap<>();
    private final ReferenceQueue<StaticObject> deadQueue = new ReferenceQueue<>();

    // commits visited by the last read walk, to pre-size the next walk's visited set
    private int lastWalkSize = 16;

    // commits coloured by the last merge walk, to pre-size the next walk's colour map
    private int lastMergeWalkSize = 16;

    public BranchingView(Supplier<SyncState> bindState, AtomicLong deadObjectCount) {
        this.bindState = bindState;
        this.deadObjectCount = deadObjectCount;
    }

    /** This thread's sync state, bound on first use. */
    SyncState state() {
        SyncState s = unboundOrState;
        if (s == null) {
            s = bindState.get();
            assert s != null : "binding a view produced no sync state";
            unboundOrState = s;
        }
        return s;
    }

    private void setHead(Commit c) {
        state().set(c);
    }

    boolean stagingEmpty() {
        return writtenObjects.isEmpty() && writtenArrays.isEmpty();
    }

    int stagingSize() {
        int writes = 0;
        for (WorkingObject w : writtenObjects.values()) {
            writes += w.written.size();
        }
        for (WorkingArray a : writtenArrays.values()) {
            writes += a.written.cardinality();
        }
        return writes;
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
            ObjectNumber number = deadObject.number;
            trackedObjects.remove(number);
            WorkingArray array = workingArrays.remove(number);
            if (array != null) {
                writtenArrays.remove(number);
                VersioningStats.onDrainArray(array.written.cardinality());
            }
            workingObjects.remove(number);
            WorkingObject written = writtenObjects.remove(number);
            if (written != null) {
                VersioningStats.onDrainStagedFields(written.written.size());
            }
        }
    }

    // Track that an object is in the staged objects or arrays, so that it can be dropped from
    // them when it dies
    private void track(StaticObject object) {
        trackedObjects.computeIfAbsent(object.getObjectNumber(), n -> new StagedReference(object, n, deadQueue));
    }

    private WorkingObject workingObject(StaticObject holder) {
        WorkingObject w = workingObjects.get(holder.getObjectNumber());
        if (w == null) {
            w = new WorkingObject();
            workingObjects.put(holder.getObjectNumber(), w);
            track(holder);
        }
        return w;
    }

    void stageField(StaticObject holder, Field field, Object value) {
        assert activated : "a write through a view before its thread started";
        WorkingObject w = workingObject(holder);
        w.values.put(field, value);
        if (w.written.add(field)) {
            writtenObjects.put(holder.getObjectNumber(), w);
        }

        emptyDeadQueue();
    }

    /** The value this view sees: its working value, else the committed value, else version 0 (the raw slot). */
    Object readField(StaticObject holder, Field field) {
        assert activated : "a read through a view before its thread started";
        WorkingObject w = workingObject(holder);
        Object value = w.values.get(field);
        if (value != null) {
            VersioningStats.onWorkingFieldHit();
            return value;
        }

        if (state().commit != null) {
            value = getLastCommittedValue(holder.getObjectNumber(), field);
        }
        if (value == null) {
            value = field.getRawValue(holder);
        }
        w.values.put(field, value);
        return value;
    }

    /** The newest committed value of the field under the head, else null. The head must be set. */
    private Object getLastCommittedValue(ObjectNumber number, Field field) {
        // The pathological cases are:
        // 1) the value is all the way back at version 0

        // Walk back through the DAG, building a priority queue where
        // the newest commits are to be explored first, the moment we find a
        // value, we know we have the newest value, if we don't find a value
        // we add the parents to the queue (in priority order).
        // We are assuming that this a conflict free graph, so there can
        // only be _one_ latest value

        Set<Commit> visited = HashSet.newHashSet(lastWalkSize);
        Queue<Commit> heads = new PriorityQueue<Commit>((commit1, commit2) ->
            Integer.compare(commit2.generation(), commit1.generation())
        );

        visited.add(state().commit);

        Object found = null;
        Commit current = state().commit;
        while (current != null) {
            // parents before changes: a concurrent absorb swaps changes then parents, so new parents
            // guarantee the new changes are seen too
            Set<Commit> parents = current.parents();
            found = current.changesTo(number) instanceof FieldChanges f ? f.get(field) : null;
            if (found != null) {
                break;
            }

            // Most of the history is chains of single parent commits, so if
            // there is only one parent and it is at least as new as anything
            // waiting in the queue, it would be the next thing popped anyway.
            // In that case just step straight to it and skip the queue.
            boolean isOnlyParent = parents.size() == 1;
            Commit next = null;
            for (Commit p : parents) {
                if (!visited.add(p)) {
                    continue;
                }
                boolean isNextToPop = heads.isEmpty() || p.generation() >= heads.peek().generation();
                if (isOnlyParent && isNextToPop) {
                    next = p;
                } else {
                    heads.add(p);
                }
            }
            current = next != null ? next : heads.poll();
        }

        lastWalkSize = visited.size();
        VersioningStats.onReadWalk(visited.size(), found != null);
        return found;
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

        // The written fields of each object, plus the written elements of each array, whose written marks
        // are cleared; the working values stay, as they are now what the head says
        Map<ObjectNumber, ObjectChanges> changes = new HashMap<>();
        int fields = 0;
        for (Map.Entry<ObjectNumber, WorkingObject> e : writtenObjects.entrySet()) {
            changes.put(e.getKey(), FieldChanges.of(e.getValue()));
            fields += e.getValue().written.size();
            e.getValue().written.clear();
        }
        writtenObjects.clear();
        long arrayElements = 0;
        int arrays = 0;
        for (Map.Entry<ObjectNumber, WorkingArray> e : writtenArrays.entrySet()) {
            ArrayChanges a = ArrayChanges.of(e.getValue());   // copies the bits and values
            changes.put(e.getKey(), a);
            arrayElements += a.writeCount();
            arrays++;
            e.getValue().written.clear();
        }
        writtenArrays.clear();

        if (changes.isEmpty()) {
            return;
        }

        // staging only grows between commits, so its size now is its peak since the last one
        VersioningStats.observeStaging(fields + arrayElements);
        VersioningStats.onCommit(fields, arrays, arrayElements);

        setHead(nextCommit(state().commit, changes));
    }

    /**
     * The commit that records {@code changes} on top of {@code head} ({@code null} before the first
     * commit): a new commit, or {@code head} itself when the changes fold into it.
     */
    Commit nextCommit(Commit head, Map<ObjectNumber, ObjectChanges> changes) {
        return head == null ? Commit.first(changes) : head.extend(changes);
    }

    /** A merge commit of this view's head {@code mine} and {@code theirs}. */
    Commit mergeCommit(Commit mine, Commit theirs) {
        return Commit.merge(mine, theirs);
    }

    /** Called once this view's head has taken in {@code from}'s history: adopted, fast-forwarded or merged. */
    void tookIn(SyncState from) {
    }

    /** Called once a push has pointed {@code to} at this view's head. */
    void pushedTo(SyncState to) {
    }

    /** Shallow compression from this thread's head (no-op if it has no head yet). */
    private void compress() {
        if (state().commit != null) {
            state().commit.compressUpwards();
        }
    }

    private enum CommitColour { MINE, THEIRS, BOTH; };

    /**
     * Merge {@code from}'s commit into this branch. Returns a set of locations written by both
     * branches (empty means none). Staging must be empty — every sync action (lock acquire, join,
     * volatile read/write) commits first.
     */
    ConflictingLocations merge(SyncState from) {
        assert stagingEmpty() : "merging with uncommitted writes";
        Commit myHead = state().commit;
        Commit theirHead = from.commit;
        if (myHead == null) {
            // either pickup the other commit as head (there is nothing to merge)
            // or the genuinely is no operation to do here
            if (theirHead != null) {
                setHead(theirHead);
                tookIn(from);
                // our working objects and arrays were read from version 0 alone, so may be stale against
                // the adopted history; with no head we have never committed, so none has written marks
                workingObjects.clear();
                workingArrays.clear();
            }
            return ConflictingLocations.NONE;
        }

        if (theirHead == null) {
            // just return as there is nothing to merge
            return ConflictingLocations.NONE;
        }

        if (theirHead == myHead) {
            // no point in doing this
            return ConflictingLocations.NONE;
        }

        List<Commit> mineOnly = new ArrayList<>();
        List<Commit> theirsOnly = new ArrayList<>();
        divergence(from, myHead, theirHead, mineOnly, theirsOnly);

        if (theirsOnly.isEmpty()) {
            // they are an ancestor of us so nothing to do
            return ConflictingLocations.NONE;
        }

        return mergeDiverged(from, theirHead, mineOnly, theirsOnly);
    }

    /**
     * Fill {@code mineOnly} with the commits in {@code myHead}'s history that are not in {@code
     * theirHead}'s, and {@code theirsOnly} with the reverse, each newest first (by generation).
     * {@code from} is the sync state holding {@code theirHead}.
     */
    void divergence(SyncState from, Commit myHead, Commit theirHead, List<Commit> mineOnly, List<Commit> theirsOnly) {
        Queue<Commit> heads = new PriorityQueue<>((c1, c2) ->
            Integer.compare(c2.generation(), c1.generation())
        );
        Map<Commit, CommitColour> colours = HashMap.newHashMap(lastMergeWalkSize);

        heads.add(myHead);
        heads.add(theirHead);

        colours.put(myHead, CommitColour.MINE);
        colours.put(theirHead, CommitColour.THEIRS);

        int nonBoth = 2;
        while (nonBoth > 0) {
            Commit head = heads.remove();
            CommitColour colour = colours.get(head);

            // We've now visited everything below this node,
            // So we now know the colour of the node and whose
            // change set the changes belong to
            if (colour == CommitColour.MINE) {
                mineOnly.add(head);
                nonBoth--;
            } else if (colour == CommitColour.THEIRS) {
                theirsOnly.add(head);
                nonBoth--;
            }

            for (Commit p : head.parents()) {
                CommitColour parentColour = colours.get(p);

                if (parentColour == null) {
                    colours.put(p, colour);
                    heads.add(p);

                    if (colour != CommitColour.BOTH) {
                        nonBoth++;
                    }
                } else if (parentColour != colour) {
                    colours.put(p, CommitColour.BOTH);

                    if (parentColour != CommitColour.BOTH) {
                        nonBoth--;
                    }
                }
            }
        }
        lastMergeWalkSize = colours.size();
    }

    // The merge once each side's own commits are known: check for conflicts, install their writes, then
    // fast-forward or make a merge commit
    private ConflictingLocations mergeDiverged(SyncState from, Commit theirHead, List<Commit> mineOnly, List<Commit> theirsOnly) {
        Map<ObjectNumber, Set<Field>> mineFieldsWritten = new HashMap<>();
        Map<ObjectNumber, BitSet> mineArrayWritten = new HashMap<>();
        for (Commit c : mineOnly) {
            c.changes().forEach((number, changes) -> {
                if (changes instanceof FieldChanges f) {
                    mineFieldsWritten.computeIfAbsent(number, n -> new HashSet<>()).addAll(f.fields());
                } else if (changes instanceof ArrayChanges a) {
                    mineArrayWritten.computeIfAbsent(number, n -> new BitSet()).or(a.written());
                }
            });
        }

        // First check for conflicts between mine and theirs
        if (!mineFieldsWritten.isEmpty() || !mineArrayWritten.isEmpty()) {
            Map<ObjectNumber, Set<Field>> fieldConflicts = new HashMap<>();
            Map<ObjectNumber, BitSet> arrayConflicts = new HashMap<>();
            for (Commit c : theirsOnly) {
                c.changes().forEach((number, changes) -> {
                    if (changes instanceof FieldChanges f) {
                        Set<Field> mine = mineFieldsWritten.get(number);
                        if (mine != null) {
                            for (Field field : f.fields()) {
                                if (mine.contains(field)) {
                                    fieldConflicts.computeIfAbsent(number, n -> new HashSet<>()).add(field);
                                }
                            }
                        }
                    } else if (changes instanceof ArrayChanges a) {
                        BitSet mine = mineArrayWritten.get(number);
                        if (mine != null && mine.intersects(a.written())) {
                            BitSet both = (BitSet) mine.clone();
                            both.and(a.written());
                            arrayConflicts.computeIfAbsent(number, n -> new BitSet()).or(both);
                        }
                    }
                });
            }
            if (!fieldConflicts.isEmpty() || !arrayConflicts.isEmpty()) {
                return new ConflictingLocations(fieldConflicts, arrayConflicts);
            }
        }

        // No conflicts, so install their writes into our working. This is instead
        // of dropping the value and picking up the new values on read
        for (Commit c : theirsOnly.reversed()) {
            c.changes().forEach((number, changes) -> {
                if (changes instanceof FieldChanges f) {
                    WorkingObject w = workingObjects.get(number);
                    if (w != null) {
                        f.applyTo(w);
                    }
                } else if (changes instanceof ArrayChanges a) {
                    WorkingArray working = workingArrays.get(number);
                    if (working != null) {
                        a.applyTo(working.storage);
                    }
                }
            });
        }

        if (mineOnly.isEmpty()) {
            // we are some ancestor of theirs, so just fast forward
            setHead(theirHead);
            tookIn(from);

            // At this point, we have also moved our head, so try to compress
            // the history as we dropped a ref to part way through the
            // history
            compress();
            return ConflictingLocations.NONE;
        }

        // create the merge of the parents
        Commit mergeCommit = mergeCommit(state().commit, theirHead);
        VersioningStats.onMerge();
        setHead(mergeCommit);
        tookIn(from);
        return ConflictingLocations.NONE;
    }

    // pull from another commit and report any conflicting locations
    ConflictingLocations pull(SyncState from) {
        commitStaging();
        return merge(from);
    }

    void commit() {
        commitStaging();

        // after commit, try to compress the DAG
        compress();
    }

    // commit, compress and push the changes to the 'to' location
    void push(SyncState to) {
        // let go of to's reference first: if to held our head, the head may then be exclusive to us
        // and the commit can fold into it
        to.set(null);
        commit();
        to.set(state().commit);
        pushedTo(to);
    }

    // Walk back to the root and find the ordered writes to the array reachable
    // from the current head, and then apply those writes.
    // We do this more general walk, instead of the stop at a first commit for a field, because arrays require
    // writes to all of their indexes, so figuring out which array commits shadow which others is more complicated
    // than collecting history and applying it
    private WorkingArray createArrayFromHistory(StaticObject array) {
        VersioningStats.onWorkingArrayBuild();
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
            if (c.changesTo(number) instanceof ArrayChanges changes) {
                changes.applyTo(storage);
            }
        }

        return new WorkingArray(storage);
    }

    public Object arrayForRead(StaticObject array, int from, int to) {
        assert activated : "a read through a view before its thread started";
        WorkingArray working = workingArrays.get(array.getObjectNumber());
        if (working != null) {
            VersioningStats.onWorkingArrayHit();
            return working.storage;
        }

        working = createArrayFromHistory(array);
        workingArrays.put(array.getObjectNumber(), working);
        track(array);
        return working.storage;
    }

    public Object arrayForWrite(StaticObject array, int from, int to) {
        assert activated : "a write through a view before its thread started";
        WorkingArray working = workingArrays.get(array.getObjectNumber());
        if (working == null) {
            working = createArrayFromHistory(array);
            workingArrays.put(array.getObjectNumber(), working);
            track(array);
        } else {
            VersioningStats.onWorkingArrayHit();
        }

        working.markWrittenRange(from, to);
        writtenArrays.put(array.getObjectNumber(), working);
        return working.storage;
    }

    // --- Pending conflict (raised at the bytecode safe point, asked through the model) ---

    void setPendingConflict(ConflictReport conflict) {
        this.pendingConflict = conflict;
    }

    boolean hasPendingConflict() {
        return pendingConflict != null;
    }

    ConflictReport takePendingConflict() {
        ConflictReport c = pendingConflict;
        pendingConflict = null;
        return c;
    }
}
