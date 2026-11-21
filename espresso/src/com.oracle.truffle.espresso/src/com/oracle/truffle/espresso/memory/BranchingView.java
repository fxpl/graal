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

import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * A guest thread's view in the branching model: what it reads and writes between synchronisation
 * actions, and the pulls and pushes the model makes at them. A {@link BranchingRepresentation} makes one
 * per thread, and alone knows how its versions are kept. Owned by one guest thread.
 */
interface BranchingView extends MemoryView {

    /** The thread has started, so its accesses may go through the view. */
    void activate();

    /** The value this view sees for {@code field} of {@code holder}. */
    Object readField(StaticObject holder, Field field);

    /** Write {@code value} to {@code field} of {@code holder}, seen by other threads only after a push. */
    void writeField(StaticObject holder, Field field, Object value);

    /** The storage to read elements {@code [from, to)} of {@code array} from. */
    Object arrayForRead(StaticObject array, int from, int to);

    /** The storage to write elements {@code [from, to)} of {@code array} to. */
    Object arrayForWrite(StaticObject array, int from, int to);

    /** Writes made since this view last pushed or pulled, for the schedule explorer. */
    int pendingWriteCount();

    /** Merge in what {@code from} holds, reporting the locations both sides wrote with no order between them. */
    ConflictingLocations pull(SyncState<?> from);

    /** Hand what this view holds, its own writes included, to {@code to}. */
    void push(SyncState<?> to);

    /** The thread has finished, so its writes become what a join of it pulls. */
    void end();

    void setPendingConflict(ConflictReport conflict);

    boolean hasPendingConflict();

    ConflictReport takePendingConflict();
}
