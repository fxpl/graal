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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.memory.ModelEvents.MergeOutcome;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * A guest thread's view in the branching model, kept as snapshots: the thread's sync state holds a
 * snapshot of every versioned object's latest writes, which sync states share by structure, and the view
 * keeps working copies of what the thread has accessed, with its unreleased writes marked. Owned by one
 * guest thread.
 */
final class SnapshotView implements BranchingView {

    // This thread's sync state, bound on first use
    private SnapshotSyncState unboundOrState;
    private final Supplier<SyncState<?>> bindState;

    // Set when the thread starts (or, for the thread that runs main, when versioning is turned on).
    // A read before then would see version 0 regardless of what the thread inherits, so accesses
    // assert it.
    private boolean activated;

    // this thread's clock before it binds to its state, which from then on holds the thread's clock
    private final VectorClock start;

    private ConflictReport pendingConflict;

    // this thread's working copies, with its uncommitted writes marked
    private final Workspace workspace = new Workspace();

    // A count shared between views for the number of objects purged,
    // and the number we saw at the last purge
    private final AtomicLong deadObjectCount;
    private long deadSetSizeAtLastPurge;

    SnapshotView(Supplier<SyncState<?>> bindState, AtomicLong deadObjectCount, VectorClock start) {
        this.bindState = bindState;
        this.deadObjectCount = deadObjectCount;
        this.start = start;
    }

    // every sync state under this view's model was made by SnapshotRepresentation, so it holds snapshot
    private static SnapshotSyncState asSnapshotState(SyncState<?> state) {
        return (SnapshotSyncState) state;
    }

    /**
     * This thread's sync state, bound on first use, which holds the thread's snapshot and clock. A spawned
     * thread's state holds what its parent pushed, so its clock becomes this thread's own, having seen
     * everything the parent's had.
     */
    private SnapshotSyncState state() {
        SnapshotSyncState s = unboundOrState;
        if (s == null) {
            SyncState<?> bound = bindState.get();
            assert bound != null : "binding a view produced no sync state";
            s = asSnapshotState(bound);
            s.clock = s.clock == null ? start : start.join(s.clock);
            unboundOrState = s;
        }
        return s;
    }

    // this thread's clock, which decides for each location written on both sides which write is later
    private VectorClock clock() {
        return state().clock;
    }

    @Override
    public void activate() {
        activated = true;
    }

    // the working value, else the snapshot's, else version 0 in the raw slot
    @Override
    public Object readField(StaticObject holder, Field field) {
        assert activated : "a read through a view before its thread started";
        Object value = workspace.workingField(holder, field);
        if (value != null) {
            ModelEvents.workingFieldHit();
            return value;
        }

        Snapshot snapshot = state().get();
        value = snapshot.get(holder.getObjectNumber()) instanceof StampedFieldChanges f ? f.value(field) : null;
        ModelEvents.snapshotRead(value != null);

        if (value == null) {
            value = field.getRawValue(holder);
        }

        workspace.addWorkingField(holder, field, value);
        return value;
    }

    // into the working copy only, the snapshot changes when the write leaves the thread
    @Override
    public void writeField(StaticObject holder, Field field, Object value) {
        assert activated : "a write through a view before its thread started";
        workspace.writeField(holder, field, value);
    }

    @Override
    public Object arrayForRead(StaticObject array, int from, int to) {
        assert activated : "a read through a view before its thread started";
        return workingArray(array);
    }

    @Override
    public Object arrayForWrite(StaticObject array, int from, int to) {
        assert activated : "a write through a view before its thread started";
        Object working = workingArray(array);
        workspace.writeArray(array, from, to);
        return working;
    }

    // the working array, else version 0 with the snapshot's writes copied over it
    private Object workingArray(StaticObject array) {
        Object working = workspace.workingArray(array);
        if (working != null) {
            ModelEvents.workingArrayHit();
            return working;
        }

        ModelEvents.workingArrayBuilt();
        working = array.copyOfRawArray(array.getKlass().getLanguage());
        if (state().get().get(array.getObjectNumber()) instanceof StampedArrayChanges a) {
            a.applyTo(working);
        }

        workspace.addWorkingArray(array, working);
        return working;
    }

    @Override
    public int pendingWriteCount() {
        return workspace.stagedCounts().total();
    }

    /** Turn staged writes into a new commit on this thread's branch (no-op if nothing staged). */
    private void commitStaging() {
        // The staged fields of each live object, plus the staged elements of each live array, which are then
        // no longer staged; the working values stay, as they are now what the head says
        Map<ObjectNumber, ObjectChanges> changes = workspace.stagedChanges();
        workspace.commitStaged();

        if (changes.isEmpty()) {
            return;
        }

        ModelEvents.Committing span = new ModelEvents.Committing();
        span.begin();
        state().clock = clock().tick();

        state().set(state().get().withChangesAt(changes, clock().epoch()));
        span.commit();
    }

    // the share of a snapshot that must have died since the last purge before it is walked
    private static final double PURGE_FRACTION = 0.125;

    // drop dead objects from this thread's snapshot, once enough have died to be worth walking it
    private void purgeDead() {
        long deadCount = deadObjectCount.get();
        Snapshot snapshot = state().get();
        // deaths are counted across the program, so this overstates how much of the snapshot is dead
        if (deadCount - deadSetSizeAtLastPurge < snapshot.size() * PURGE_FRACTION) {
            return;
        }
        ModelEvents.Purge span = new ModelEvents.Purge();
        span.begin();
        state().set(snapshot.withoutDead());
        span.commit();
        deadSetSizeAtLastPurge = deadCount;
    }

    // put the working writes into this thread's snapshot, then hand it to the sync state
    @Override
    public void push(SyncState<?> pushTo) {
        SnapshotSyncState to = asSnapshotState(pushTo);
        commitStaging();
        // before the hand over, so the sync state is given a purged snapshot
        purgeDead();

        to.set(state().get());
        to.clock = clock();
    }

    // put the working writes into this thread's snapshot, then merge in theirs
    @Override
    public ConflictingLocations pull(SyncState<?> pullFrom) {
        SnapshotSyncState from = asSnapshotState(pullFrom);
        commitStaging();

        if (from.get() == Snapshot.EMPTY) {
            ModelEvents.mergeSkipped(MergeOutcome.NOTHING);
            return ConflictingLocations.NONE;
        }
        if (from.get() == state().get()) {
            ModelEvents.mergeSkipped(MergeOutcome.SAME);
            return ConflictingLocations.NONE;
        }
        if (from.clock.happensBefore(state().clock)) {
            // I have seen everything they have
            ModelEvents.mergeSkipped(MergeOutcome.CLOCK_INCLUDED);
            return ConflictingLocations.NONE;
        }

        if (state().clock.happensBefore(from.clock)) {
            state().set(from.get());
            state().clock = state().clock.join(from.clock).tick();
            workspace.clear();
            // their snapshot may hold objects that have died since it was pushed
            purgeDead();
            ModelEvents.mergeSkipped(MergeOutcome.CLOCK_FAST_FORWARD);
            return ConflictingLocations.NONE;
        }

        // the merge span holds the union as its walk, as the DAG's holds its colouring walk
        ModelEvents.Merge event = new ModelEvents.Merge();
        event.begin();
        ModelEvents.MergeWalk walk = new ModelEvents.MergeWalk();
        walk.begin();
        Snapshot.Merged merged = state().get().merge(from.get(), clock(), from.clock);
        walk.commit();

        if (merged.hasConflicts()) {
            recordMerge(event, MergeOutcome.CONFLICT, merged);
            return merged.conflicts();
        }

        merged.taken().forEach(workspace::applyPulled);
        state().set(merged.snapshot());
        state().clock = clock().join(from.clock).tick();
        // after the merge, which may have brought back objects that died since their snapshot was pushed
        purgeDead();

        recordMerge(event, MergeOutcome.MERGED, merged);
        return ConflictingLocations.NONE;
    }

    // visited counts objects here, not the DAG's commits, both those resolved and those taken whole, so that as
    // for the DAG what is in both is visited less the two sides, and there is no side only mine holds to count
    private static void recordMerge(ModelEvents.Merge event, MergeOutcome outcome, Snapshot.Merged merged) {
        if (event.shouldCommit()) {
            event.outcome = outcome.name();
            event.visited = merged.resolved() + merged.theirsOnly();
            event.mineOnly = 0;
            event.theirsOnly = merged.theirsOnly();
            event.commit();
        }
    }

    // put the working writes into this thread's snapshot, which its own state holds for a join
    @Override
    public void end() {
        commitStaging();
        ModelEvents.threadClock(clock());
    }

    @Override
    public void setPendingConflict(ConflictReport conflict) {
        this.pendingConflict = conflict;
    }

    @Override
    public boolean hasPendingConflict() {
        return pendingConflict != null;
    }

    @Override
    public ConflictReport takePendingConflict() {
        ConflictReport c = pendingConflict;
        pendingConflict = null;
        return c;
    }
}
