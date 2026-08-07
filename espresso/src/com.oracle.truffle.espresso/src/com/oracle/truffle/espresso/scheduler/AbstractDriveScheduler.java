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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * Common machinery for the record/replay drivers (design/schedule-explorer.md §5.4). Handles the
 * bits that are identical whether we are recording or replaying: activation, Filter A (application
 * threads only), and assigning each synchronization subject a run-stable integer id. Subclasses
 * implement {@link #onAppSeam} (called for every application-thread seam, in the acting thread) and
 * {@link #report}.
 *
 * <p>Subject ids are first-seen ordinals rather than {@code System.identityHashCode}, which is not
 * stable across runs (§5.5). Given deterministic allocation order, record and replay assign matching
 * ids, so a schedule line refers to the same monitor / thread object in both runs.
 */
public abstract class AbstractDriveScheduler implements Scheduler {

    private volatile boolean active = false;

    // Filter A: application threads only (main + its transitive spawns). Mirrors TracingScheduler:
    // VM daemons start during bootstrap, before activation, so they are never tagged.
    private final ThreadLocal<Boolean> appThread = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final java.util.Set<StaticObject> spawnedByApp = ConcurrentHashMap.newKeySet();

    private final AtomicLong seq = new AtomicLong();
    private final Map<StaticObject, Integer> subjectIds = new ConcurrentHashMap<>();
    private final AtomicInteger nextSubjectId = new AtomicInteger();

    @Override
    public final boolean isActive() {
        return active;
    }

    @Override
    public void activate() {
        active = true;
        appThread.set(Boolean.TRUE);   // runs on the main thread (BeginUserCodeNode)
    }

    @Override
    @TruffleBoundary
    public final void atSeam(SyncOp op, StaticObject subject, int pendingWrites) {
        if (!active) {
            return;
        }
        boolean hasSubject = subject != null && !StaticObject.isNull(subject);
        // A freshly started thread inherits app-ness from the app thread that spawned it.
        if (op == SyncOp.THREAD_START && hasSubject && spawnedByApp.remove(subject)) {
            appThread.set(Boolean.TRUE);
        }
        if (!appThread.get()) {
            return;
        }
        if (op == SyncOp.SPAWN && hasSubject) {
            spawnedByApp.add(subject);   // so the child's THREAD_START promotes it
        }
        long n = seq.incrementAndGet();
        int subjectId = hasSubject ? subjectIds.computeIfAbsent(subject, s -> nextSubjectId.getAndIncrement()) : -1;
        onAppSeam(n, Thread.currentThread().getName(), op, subject, hasSubject, subjectId, pendingWrites);
    }

    /** Called for every application-thread seam, on the acting host thread, after id assignment. */
    protected abstract void onAppSeam(long seq, String thread, SyncOp op, StaticObject subject,
                    boolean hasSubject, int subjectId, int pendingWrites);
}
