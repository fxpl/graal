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

import java.lang.ref.Cleaner;
import java.util.function.Supplier;

/**
 * The branching model's versions kept as snapshots: each sync state holds a snapshot of every versioned
 * object's latest writes, and snapshots share by structure whatever neither changed. There is no
 * history, so a version no sync state holds is collected by the GC. Each thread keeps a vector clock, as
 * a merge needs one to tell which of two writes to a location is later.
 */
final class SnapshotRepresentation implements BranchingRepresentation {

    @Override
    public BranchingView newView(Supplier<SyncState<?>> bindState, int threadId) {
        return new SnapshotView(bindState, VectorClock.start(threadId));
    }

    @Override
    public SyncState<?> newSyncState(SyncState.Kind kind) {
        return new SnapshotSyncState(kind);
    }

    // a dead object can never be read again, so its entries only cost memory, and are not yet dropped
    @Override
    public void onObjectDied(ObjectNumber number) {
    }

    // nothing is reference counted, so what the state held is collected once nothing else holds it
    @Override
    public void onThreadDied(SyncState<?> state) {
    }

    // nothing to report yet
    @Override
    public void dispose(Cleaner cleaner) {
    }
}
