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

import java.io.PrintStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * Observation-only scheduler: prints each synchronization seam as it fires — a global sequence
 * number, the host thread, the op, and a subject label. Changes no program behaviour. The trace it
 * emits is the empirical seam sequence used to validate the seam table in
 * {@code design/schedule-explorer.md} §4 (does what we think fires actually fire, and only that?).
 *
 * <p>Selected by {@code --java.ScheduleExplore=trace}.
 */
public final class TracingScheduler implements Scheduler {

    private final PrintStream out = System.err;
    private final AtomicLong seq = new AtomicLong();
    private volatile boolean active = false;

    // Filter A: trace application threads only. A thread is "application" if it is the main thread
    // (tagged when the tracer activates — activation runs on the main thread at the user-code
    // boundary) or was spawned by an application thread. VM daemons (Reference Handler, Finalizer,
    // Common-Cleaner, ...) are started during bootstrap, before activation, so they are never tagged
    // and never traced. Each guest thread has its own host thread, so a host ThreadLocal is a fast
    // per-seam gate; the spawn->start handoff crosses host threads, so it uses a small identity set.
    private final ThreadLocal<Boolean> appThread = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final Set<StaticObject> spawnedByApp = ConcurrentHashMap.newKeySet();

    // Number of application threads currently in play (spawned/main, not yet ended). A seam is a
    // potential scheduling decision only when this is >= 2 — the coarsest partial-order reduction and
    // the precondition for DPOR (a transition is only a choice if it is co-enabled with another). The
    // live= column lets us measure how many of the raw seams are even candidates.
    private final AtomicInteger liveApp = new AtomicInteger();

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void activate() {
        active = true;
        appThread.set(Boolean.TRUE);   // runs on the main thread (BeginUserCodeNode)
        liveApp.set(1);                // main
    }

    @Override
    @TruffleBoundary
    public void atSeam(SyncOp op, StaticObject subject) {
        if (!active) {
            return;
        }
        // A freshly started thread inherits app-ness if an application thread spawned it. Promote
        // before the gate so this thread's own THREAD_START (and everything after) is traced.
        if (op == SyncOp.THREAD_START && subject != null && spawnedByApp.remove(subject)) {
            appThread.set(Boolean.TRUE);
        }
        if (!appThread.get()) {
            return;   // not descended from main => VM/daemon thread; skip.
        }
        // Maintain the live application-thread count. Increment before printing a SPAWN (the child is
        // now in play); decrement after printing a THREAD_END (self still counts at its own end).
        if (op == SyncOp.SPAWN && subject != null && !StaticObject.isNull(subject)) {
            spawnedByApp.add(subject);
            liveApp.incrementAndGet();
        }
        long n = seq.incrementAndGet();
        out.printf("[seam %4d] live=%d %-18s %-14s %s%n", n, liveApp.get(), Thread.currentThread().getName(), op, label(subject));
        if (op == SyncOp.THREAD_END) {
            liveApp.decrementAndGet();
        }
    }

    private static String label(StaticObject subject) {
        if (subject == null || StaticObject.isNull(subject)) {
            return "-";
        }
        return subject.getKlass().getType() + "@" + Integer.toHexString(System.identityHashCode(subject));
    }
}
