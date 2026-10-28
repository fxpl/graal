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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.espresso.memory.UserCodeLocation;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * Observation-only scheduler; changes no program behaviour. Reports the synchronization points that
 * application threads pass, selected by {@code --java.ScheduleExplore} (see {@link Mode}):
 *
 * <ul>
 * <li>{@code summary}: nothing per sync point — only the closing counts.</li>
 * <li>{@code trace}: one line per sync point as it fires — the acting thread, op, subject and guest
 * site, tagged {@code RELEASED(n)} when a release makes n staged writes available to other threads,
 * and {@code ACQUIRED(n)} when an acquire (monitor enter, wait re-acquire, thread start) brings in n
 * writes released by other threads.</li>
 * </ul>
 *
 * See {@code design/schedule-explorer.md}.
 */
public final class TracingScheduler implements Scheduler {

    private final PrintStream out = System.err;
    private final AtomicLong seq = new AtomicLong();
    private volatile boolean active = false;

    // Trace application threads only. A thread is "application" if it is the main thread (tagged when
    // the tracer activates — activation runs on the main thread at the user-code boundary) or was
    // spawned by an application thread. VM daemons (Reference Handler, Finalizer, Common-Cleaner, ...)
    // are started during bootstrap, before activation, so they are never tagged and never traced. Each
    // guest thread has its own host thread, so a host ThreadLocal is a fast per-seam gate; the
    // spawn->start handoff crosses host threads, so it uses a small identity set.
    private final ThreadLocal<Boolean> appThread = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final Set<StaticObject> spawnedByApp = ConcurrentHashMap.newKeySet();

    /** What this tracer emits. Both modes end with the closing counts. */
    public enum Mode {
        /** Nothing per sync point — only the closing counts. */
        SUMMARY,
        /** One line per sync point as it fires. */
        TRACE
    }

    private final Mode mode;
    private final EspressoContext context;   // for the guest site (trace)
    private volatile long baseNanos;          // set at activation; timestamps are relative to it

    // Closing counts. syncPoints: every sync point an application thread passed. released: the releases
    // that made at least one staged write available to other threads. acquired: the acquires that
    // brought in at least one write released by another thread.
    private final AtomicInteger syncPoints = new AtomicInteger();
    private final AtomicInteger released = new AtomicInteger();
    private final AtomicInteger acquired = new AtomicInteger();

    public TracingScheduler(Mode mode, EspressoContext context) {
        this.mode = mode;
        this.context = context;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void activate() {
        active = true;
        baseNanos = System.nanoTime();   // t=0 at the user-code boundary
        appThread.set(Boolean.TRUE);   // runs on the main thread (BeginUserCodeNode)
        if (mode == Mode.TRACE) {
            out.println("# sync trace. one line per synchronization point an application thread passes.");
            out.println("#   [sync N] <thread>  t+<ms>  OP  <subject>  <guest site>  [RELEASED(n) | ACQUIRED(n)]");
            out.println("#   subject      the monitor / thread object;  site  the guest Class.method:line doing it");
            out.println("#   RELEASED(n)  this release made n staged writes available to other threads");
            out.println("#   ACQUIRED(n)  this acquire brought in n writes released by other threads");
        }
    }

    @Override
    @TruffleBoundary
    public void atSeam(SyncOp op, StaticObject subject, int writes) {
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
        if (op == SyncOp.SPAWN && subject != null && !StaticObject.isNull(subject)) {
            spawnedByApp.add(subject);   // so the child's THREAD_START promotes it
        }
        long n = seq.incrementAndGet();
        syncPoints.incrementAndGet();
        String tag = "";
        if (writes > 0) {
            if (op.isRelease()) {
                released.incrementAndGet();
                tag = " RELEASED(" + writes + ")";
            } else {
                acquired.incrementAndGet();
                tag = " ACQUIRED(" + writes + ")";
            }
        }
        if (mode == Mode.TRACE) {
            out.printf(Locale.ROOT, "[sync %4d] %-12s t+%8.1fms %-14s %-28s %s%s%n",
                            n, Thread.currentThread().getName(), millis(), op, label(subject), site(), tag);
        }
    }

    @Override
    @TruffleBoundary
    public void report() {
        if (!active) {
            return;
        }
        out.printf("[explore] %d sync points; %d releases and %d acquires carried writes%n",
                        syncPoints.get(), released.get(), acquired.get());
    }

    /** Milliseconds since activation (t=0 at the user-code boundary). Observation only — never fed to
     * the recorded schedule, so it does not compromise replay determinism. */
    private double millis() {
        return (System.nanoTime() - baseNanos) / 1_000_000.0;
    }

    /**
     * The guest source site performing this sync point: the top application frame, as
     * {@code Class.method:line}, or {@code ?} if none. Valid because we run synchronously on the acting
     * thread inside the seam, so its frames are live.
     */
    private String site() {
        return UserCodeLocation.current(context);
    }

    private static String label(StaticObject subject) {
        if (subject == null || StaticObject.isNull(subject)) {
            return "-";
        }
        return subject.getKlass().getType() + "@" + Integer.toHexString(System.identityHashCode(subject));
    }
}
