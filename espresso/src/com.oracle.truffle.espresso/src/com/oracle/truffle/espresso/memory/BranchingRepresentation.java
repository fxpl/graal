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
 * How a branching model keeps its versions: it makes each thread's view, and hears of the deaths and the
 * end of the run that its kept versions may need to act on. One per model.
 */
interface BranchingRepresentation {

    /** The view of thread {@code threadId}, bound to its sync state on first use by {@code bindState}. */
    BranchingView newView(Supplier<SyncState<?>> bindState, int threadId);

    /** A new state for a synchronisation object of {@code kind}, holding this representation's versions. */
    SyncState<?> newSyncState(SyncState.Kind kind);

    /** Object {@code number} has been collected, and is already marked dead. */
    void onObjectDied(ObjectNumber number);

    /** The run is over and every guest thread has ended, so report what is kept. */
    void dispose(Cleaner cleaner);
}
