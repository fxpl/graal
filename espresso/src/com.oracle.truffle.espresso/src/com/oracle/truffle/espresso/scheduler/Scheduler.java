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
package com.oracle.truffle.espresso.scheduler;

import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * SPI for observing — and, in later slices, controlling — the guest program's synchronization seams.
 * It is intentionally separate from {@code MemoryModel}: the model decides <em>visibility</em>
 * (what a read sees), the scheduler decides <em>order</em> (which thread proceeds at a seam). Keeping
 * them independent is what lets the scheduler be cross-checked against the model rather than sharing
 * its blind spots (see {@code design/schedule-explorer.md} §6).
 *
 * <p>Slice 1 is observation only: {@link #atSeam} is invoked as each seam fires and must not change
 * program behaviour. Selected by {@code --java.ScheduleExplore}; folds away under {@code OFF}.
 * Activated at the same point as the memory model (before user {@code main}), so JDK bootstrap seams
 * are not observed.
 */
public interface Scheduler {

    /**
     * Announce that {@code op} is occurring, on {@code subject} — the monitor for the monitor,
     * wait and notify ops, the target thread for park, unpark and spawn, or {@code StaticObject.NULL}
     * where there is no natural subject (sleep, thread start/end). Called on the host thread
     * performing the op. {@code pendingWrites} is the number of writes the calling thread has staged
     * but not yet published (from the memory model; 0 under {@code shared}) — a release-type seam is a
     * <em>flow-commit</em> iff this is {@code > 0}. The context supplies it so the scheduler need not
     * reach into the model (§6 independence).
     */
    void atSeam(SyncOp op, StaticObject subject, int pendingWrites);

    /** Whether announcements should be delivered yet (i.e. we are past JDK bootstrap). */
    default boolean isActive() {
        return false;
    }

    /** Begin delivering announcements. Called once, before user {@code main}. */
    default void activate() {
    }

    /** Emit any end-of-run summary. Called once at context dispose. No-op by default. */
    default void report() {
    }
}
