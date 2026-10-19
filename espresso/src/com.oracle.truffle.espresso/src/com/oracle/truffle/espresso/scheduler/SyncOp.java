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

/**
 * The synchronization seams a guest thread can pass through. This is the vocabulary the schedule
 * explorer reasons about; it is deliberately independent of the memory model (a seam is a seam
 * whether or not any model reacts to it).
 *
 * <p>Slice 1 covers the monitor / wait-notify / park / lifecycle / sleep set. Volatile and
 * class-init seams are deferred to a later slice (they need the hot field path and the ObjectKlass
 * init-lock edit respectively). See {@code design/schedule-explorer.md} §4.
 */
public enum SyncOp {
    /** A monitor was acquired (guest {@code MONITORENTER} / JNI MonitorEnter), after the acquire. */
    MONITOR_ENTER,
    /** A monitor is about to be released (guest {@code MONITOREXIT}), before the release. */
    MONITOR_EXIT,
    /** {@code Object.wait}: the monitor is about to be released to wait. */
    WAIT_RELEASE,
    /** {@code Object.wait}: the monitor has been re-acquired on wake, before returning. */
    WAIT_REACQUIRE,
    /** {@code Object.notify}: one waiter (if any) is moved to the entry set. */
    NOTIFY,
    /** {@code Object.notifyAll}: all waiters are moved to the entry set. */
    NOTIFY_ALL,
    /** {@code Unsafe.park}: the calling thread attempts to park. */
    PARK,
    /** {@code Unsafe.unpark}: a park permit is delivered to the target thread. */
    UNPARK,
    /** {@code Thread.start}: the parent forks a child (announced on the parent, pre-start). */
    SPAWN,
    /** A freshly started guest thread is about to run its payload. */
    THREAD_START,
    /** A guest thread's payload has finished (before the terminal push). */
    THREAD_END,
    /** {@code Thread.sleep}: a timed, happens-before-free pause. */
    SLEEP;

    /**
     * An acquire gains visibility from {@code subject} (the release-acquire "acquire" side): a monitor
     * enter, the monitor re-acquire on return from wait, or a freshly started thread pulling the view
     * its parent published at spawn. The paired release is the one that most recently published the
     * same {@code subject}: spawn/start share the child's {@code Thread} object; unlock/lock share the
     * monitor; termination/join share the terminating {@code Thread} object.
     */
    public boolean isAcquire() {
        return this == MONITOR_ENTER || this == WAIT_REACQUIRE || this == THREAD_START;
    }

    /**
     * A release publishes this thread's staged view to {@code subject} (the "release" side): a monitor
     * exit, the release on entering {@code wait}, a spawn (parent publishes to the child's {@code Thread}
     * object), or a thread's termination (which a joiner acquires by waiting on the {@code Thread}
     * object).
     */
    public boolean isRelease() {
        return this == MONITOR_EXIT || this == WAIT_RELEASE || this == SPAWN || this == THREAD_END;
    }
}
