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

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A sync state holding a {@link Snapshot}. Snapshots are immutable and nothing counts references to
 * them, so the state only keeps the one released to it, and what it let go of is collected once nothing
 * else holds it.
 */
final class SnapshotSyncState extends SyncState<Snapshot> {

    // nothing released to it yet is the snapshot of nothing written
    private Snapshot snapshot = Snapshot.EMPTY;

    // the clock of the thread that last set the snapshot, null until then, which a merge needs to order
    // each pair of writes, and which for a thread's own state is that thread's clock
    VectorClock clock;

    // every live state, for the snapshots report at exit, held weakly so dead states drop out. Only kept when
    // that report is recorded, which a recording begun with the host VM decides before this class loads
    static final Set<SnapshotSyncState> ALL = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final boolean IS_REGISTERED = new ModelEvents.Snapshots().isEnabled();

    SnapshotSyncState(Kind kind) {
        super(kind);
        if (IS_REGISTERED) {
            ALL.add(this);
        }
    }

    @Override
    Snapshot get() {
        return snapshot;
    }

    @Override
    void set(Snapshot next) {
        snapshot = next;
    }

    // nothing is counted, so the snapshot is collected with this state once nothing else holds it
    @Override
    void release() {
    }
}
