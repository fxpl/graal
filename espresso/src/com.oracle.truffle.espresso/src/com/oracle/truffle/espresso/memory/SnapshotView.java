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

import java.util.function.Supplier;

import com.oracle.truffle.espresso.impl.Field;
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

    // this thread's clock, which decides for each location written on both sides which write is later
    private VectorClock clock;

    private ConflictReport pendingConflict;

    // this thread's working copies, with its uncommitted writes marked
    private final Workspace workspace = new Workspace();

    SnapshotView(Supplier<SyncState<?>> bindState, VectorClock start) {
        this.bindState = bindState;
        this.clock = start;
    }

    /** This thread's sync state, bound on first use, which holds the thread's snapshot. */
    private SnapshotSyncState state() {
        SnapshotSyncState s = unboundOrState;
        if (s == null) {
            SyncState<?> bound = bindState.get();
            assert bound != null : "binding a view produced no sync state";
            // every sync state under this view's model was made by SnapshotRepresentation, so it holds snapshots
            s = (SnapshotSyncState) bound;
            unboundOrState = s;
        }
        return s;
    }

    @Override
    public void activate() {
        throw EspressoError.unimplemented("SnapshotView.activate");
    }

    // the working value, else the snapshot's, else version 0 in the raw slot
    @Override
    public Object readField(StaticObject holder, Field field) {
        throw EspressoError.unimplemented("SnapshotView.readField");
    }

    // into the working copy only, the snapshot changes when the write leaves the thread
    @Override
    public void writeField(StaticObject holder, Field field, Object value) {
        workspace.writeField(holder, field, value);
    }

    @Override
    public Object arrayForRead(StaticObject array, int from, int to) {
        throw EspressoError.unimplemented("SnapshotView.arrayForRead");
    }

    @Override
    public Object arrayForWrite(StaticObject array, int from, int to) {
        throw EspressoError.unimplemented("SnapshotView.arrayForWrite");
    }

    @Override
    public int pendingWriteCount() {
        throw EspressoError.unimplemented("SnapshotView.pendingWriteCount");
    }

    // put the working writes into this thread's snapshot, then merge in theirs
    @Override
    public ConflictingLocations pull(SyncState<?> from) {
        throw EspressoError.unimplemented("SnapshotView.pull");
    }

    // put the working writes into this thread's snapshot, then hand it to the sync state
    @Override
    public void push(SyncState<?> to) {
        throw EspressoError.unimplemented("SnapshotView.push");
    }

    // put the working writes into this thread's snapshot, which its own state holds for a join
    @Override
    public void end() {
        throw EspressoError.unimplemented("SnapshotView.end");
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
