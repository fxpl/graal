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
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * Observation-only scheduler; changes no program behaviour. Two outputs, selected by
 * {@code --java.ScheduleExplore} (see {@link Mode}):
 *
 * <ul>
 * <li>{@code summary}: nothing per-seam — only the closing funnel counts and the authored / structural /
 * machinery split.</li>
 * <li>{@code trace}: every seam as it fires — the full firehose. One line per seam with the acting
 * thread, op, staged-write count, and subject, tagged {@code FLOW} if it publishes and {@code *} if
 * co-enabled. The view for investigating <em>what</em> synchronizes, <em>who</em> does it, and
 * <em>when</em>.</li>
 * </ul>
 *
 * Both end with the funnel counts. See {@code design/schedule-explorer.md} §3a.
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

    // Number of application threads currently RUNNABLE — live and not blocked in wait/join (a joiner
    // blocks via Object.wait on the joinee, so join is covered). This is the tighter co-enablement
    // measure: a monitor op fired while only one thread is runnable cannot be contended, so it is not a
    // decision. Upgrading the gate from live>=2 to run>=2 drops the "one thread runs while the others
    // are blocked in join" tail (e.g. the exception-message / println machinery after a joinee ends).
    // Not yet tracked as blocking: contended monitor-enter (fires its seam post-acquire) and park/sleep
    // (no wake seam) — so run= can over-count there; refined with the scheduler's enabled-set (§5).
    private final AtomicInteger runnableApp = new AtomicInteger();

    /** What this tracer emits. Both modes also print the funnel. */
    public enum Mode {
        /** Nothing per-seam — only the closing counts (the funnel + the authored/machinery split). */
        SUMMARY,
        /** Every seam as it fires — the full firehose (who / when / what / staged count). */
        TRACE
    }

    private final Mode mode;
    private final EspressoContext context;   // for guest-frame provenance (trace)
    private volatile long baseNanos;          // set at activation; timestamps are relative to it

    // The 984 -> 22 -> 6 funnel (design/schedule-explorer.md §3a), counted instead of hand-tallied.
    //   traced        : every app-thread seam that fired (the raw stream).
    //   coEnabled      : seams where >= 2 app threads were runnable — a scheduling decision needs a race.
    //   flow           : release-type seams that actually published (pendingWrites > 0) — a seam that
    //                    commits nothing cannot change any view, so its order is irrelevant under SaS.
    //   flowCoEnabled  : the intersection — the seams actually worth driving (the alphabet for the driver).
    private final AtomicInteger tracedSeams = new AtomicInteger();
    private final AtomicInteger coEnabledSeams = new AtomicInteger();
    private final AtomicInteger flowCommits = new AtomicInteger();
    private final AtomicInteger flowCommitsCoEnabled = new AtomicInteger();
    // The origin split (see classify): what a seam is to the *model*, not merely who owns the top
    // frame. authored = a synchronized the programmer wrote (app-loaded monitor). structural = the
    // model's own backbone (spawn/start/end, wait/notify, join) — always communicates in every sync
    // mode. machinery = a monitor taken by boot library/VM code (finalizer, loader map, PrintStream,
    // ConcurrentHashMap bins, ...) the program never wrote. The point of splitting structural out:
    // under MemoryModelSync=authored every FLOW lands on authored or structural, never machinery.
    private final AtomicInteger authoredSeams = new AtomicInteger();
    private final AtomicInteger structuralSeams = new AtomicInteger();
    private final AtomicInteger machinerySeams = new AtomicInteger();

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
        liveApp.set(1);                // main
        runnableApp.set(1);
        if (mode == Mode.TRACE) {
            out.println("# schedule trace. one line per synchronization seam an application thread passes.");
            out.println("#   [seam N] <thread>  t+<ms>  OP  staged=<w>  live=<L>  run=<r>  <origin>  <subject>  <guest site>  [FLAGS]");
            out.println("#   thread   the acting thread (main, Thread-1, ...)");
            out.println("#   staged=w w writes sit in the thread's private view (published if OP is a release)");
            out.println("#   live=L   application threads alive (started, not yet ended)");
            out.println("#   run=r    application threads runnable right now (r>=2 => this order could matter)");
            out.println("#   subject  the monitor / thread object;  site  the guest Class.method:line doing it");
            out.println("#   origin   what the seam is to the model:");
            out.println("#            aut = authored — a synchronized the programmer wrote (app-loaded frame)");
            out.println("#            mdl = structural — the model's backbone (spawn/start/end, wait/notify, join); always communicates");
            out.println("#            jvm = machinery — a lock boot library/VM code took (finalizer, loader map, System.out, ConcurrentHashMap, ...)");
            out.println("#   FLAGS    FLOW = this release publishes staged writes;  * = co-enabled (run>=2)");
        }
    }

    @Override
    @TruffleBoundary
    public void atSeam(SyncOp op, StaticObject subject, int pendingWrites) {
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
        // State updates that must be visible ON this seam's line (the acting thread is now in that
        // state): a spawn adds a live thread; a start/wake makes this thread runnable.
        switch (op) {
            case SPAWN -> {
                if (subject != null && !StaticObject.isNull(subject)) {
                    spawnedByApp.add(subject);   // so the child's THREAD_START promotes it
                    liveApp.incrementAndGet();   // child now in play (not yet runnable — starts at THREAD_START)
                }
            }
            case THREAD_START, WAIT_REACQUIRE -> runnableApp.incrementAndGet();
            default -> { }
        }
        long n = seq.incrementAndGet();
        // Classify this seam for the funnel. Co-enabled: another app thread is runnable right now, so
        // the order here could matter. Flow-commit: a release that actually publishes staged writes.
        boolean coEnabled = runnableApp.get() >= 2;
        boolean flow = op.isRelease() && pendingWrites > 0;
        tracedSeams.incrementAndGet();
        if (coEnabled) {
            coEnabledSeams.incrementAndGet();
        }
        if (flow) {
            flowCommits.incrementAndGet();
            if (coEnabled) {
                flowCommitsCoEnabled.incrementAndGet();
            }
        }
        // Classify this seam's origin (authored / structural / machinery — see classify) and count the
        // split. TRACE also prints the seam line — the full firehose, tagged FLOW if it publishes and *
        // if co-enabled; SUMMARY prints nothing here and only reports the totals at the end.
        Origin origin = classify(op, subject);
        switch (origin) {
            case AUTHORED -> authoredSeams.incrementAndGet();
            case STRUCTURAL -> structuralSeams.incrementAndGet();
            case MACHINERY -> machinerySeams.incrementAndGet();
        }
        if (mode == Mode.TRACE) {
            String me = Thread.currentThread().getName();
            String tag = (flow ? " FLOW" : "") + (coEnabled ? "*" : "");
            out.printf(java.util.Locale.ROOT, "[seam %4d] %-12s t+%8.1fms %-14s staged=%d live=%d run=%d %-3s %-28s %s%s%n",
                            n, me, millis(), op, pendingWrites, liveApp.get(), runnableApp.get(),
                            originTag(origin), label(subject), site(), tag);
        }
        // State updates that take effect AFTER this seam (the acting thread leaves the runnable set):
        // ending stops it; releasing the monitor to wait blocks it until its WAIT_REACQUIRE.
        switch (op) {
            case THREAD_END -> {
                runnableApp.decrementAndGet();
                liveApp.decrementAndGet();
            }
            case WAIT_RELEASE -> runnableApp.decrementAndGet();
            default -> { }
        }
    }

    @Override
    @TruffleBoundary
    public void report() {
        if (!active) {
            return;
        }
        // The funnel, as measured. flowCoEnabled is the alphabet the driver explores; the ratios show
        // how much of the raw seam stream is provably irrelevant to reorder under Sync-as-Share.
        out.printf("[explore] seams: %d traced -> %d co-enabled -> %d flow-commit -> %d flow-commit & co-enabled%n",
                        tracedSeams.get(), coEnabledSeams.get(), flowCommits.get(), flowCommitsCoEnabled.get());
        out.printf("[explore] origin: %d authored (your synchronized) / %d structural (spawn/start/end, wait/notify, join) / %d machinery (jvm/library locks)%n",
                        authoredSeams.get(), structuralSeams.get(), machinerySeams.get());
    }

    /** Milliseconds since activation (t=0 at the user-code boundary). Observation only — never fed to
     * the recorded schedule, so it does not compromise replay determinism (§5.5). */
    private double millis() {
        return (System.nanoTime() - baseNanos) / 1_000_000.0;
    }

    /**
     * The guest source site performing this seam: the top application frame, as
     * {@code Class.method:line}, or {@code ?} if none. Valid because we run synchronously on the acting
     * thread inside the seam, so its frames are live.
     */
    private String site() {
        return context.getVM().topGuestSite();
    }

    /** What a seam is to the model, not merely who owns the top frame. See {@link #authoredSeams}. */
    private enum Origin {
        AUTHORED,
        STRUCTURAL,
        MACHINERY
    }

    /**
     * Classify a seam. STRUCTURAL is the model's own synchronization backbone — thread spawn/start/end
     * and wait/notify (join rides wait-on-thread) — which always communicates in every sync mode. A
     * plain monitor is AUTHORED when its top guest frame is application code (a {@code synchronized} the
     * programmer wrote), MACHINERY when boot library/VM code took it; a monitor on a {@code Thread}
     * object is join's {@code synchronized} method, hence STRUCTURAL rather than machinery. This is the
     * exact authored-vs-machinery line {@code MemoryModelSync=authored} gates on (structural split out),
     * so the invariant holds: under {@code authored}, every FLOW is on an AUTHORED or STRUCTURAL seam.
     */
    private Origin classify(SyncOp op, StaticObject subject) {
        switch (op) {
            case SPAWN, THREAD_START, THREAD_END, WAIT_RELEASE, WAIT_REACQUIRE, NOTIFY, NOTIFY_ALL -> {
                return Origin.STRUCTURAL;
            }
            case MONITOR_ENTER, MONITOR_EXIT -> {
                if (context.getVM().topGuestFrameIsApplication()) {
                    return Origin.AUTHORED;
                }
                return isThreadObject(subject) ? Origin.STRUCTURAL : Origin.MACHINERY;
            }
            default -> {
                // PARK / UNPARK / SLEEP: not the model's communicating backbone (and never a FLOW).
                return Origin.MACHINERY;
            }
        }
    }

    private static String originTag(Origin origin) {
        return switch (origin) {
            case AUTHORED -> "aut";
            case STRUCTURAL -> "mdl";
            case MACHINERY -> "jvm";
        };
    }

    private boolean isThreadObject(StaticObject subject) {
        return subject != null && !StaticObject.isNull(subject) && context.getMeta().java_lang_Thread.isAssignableFrom(subject.getKlass());
    }

    private static String label(StaticObject subject) {
        if (subject == null || StaticObject.isNull(subject)) {
            return "-";
        }
        return subject.getKlass().getType() + "@" + Integer.toHexString(System.identityHashCode(subject));
    }
}
