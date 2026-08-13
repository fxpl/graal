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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * Observation-only scheduler; changes no program behaviour. Three outputs, selected by
 * {@code --java.ScheduleExplore} (see {@link Mode}):
 *
 * <ul>
 * <li>{@code trace}: every seam as it fires — the full firehose. One line per seam with the acting
 * thread, op, staged-write count, and subject, tagged {@code FLOW} if it publishes and {@code *} if
 * co-enabled. The view for investigating <em>what</em> synchronizes, <em>who</em> does it, and
 * <em>when</em>.</li>
 * <li>{@code story}: a reduced, programmer-legible narrative — only the events that matter to a
 * reader: each application thread's spawn / start / end, and every cross-thread information handoff
 * (thread A published staged writes at a lock / thread-object, thread B later synchronized on the same
 * subject and received them). The thousands of raw seams that publish nothing or never meet another
 * thread are dropped, because under Sync-as-Share their order cannot change any thread's view.</li>
 * <li>{@code oracle}: the completeness check only (§6a) — flags any cross-thread handoff whose
 * acquire fires no model hook.</li>
 * </ul>
 *
 * All three end with the funnel counts. See {@code design/schedule-explorer.md} §3a/§6.
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

    // Completeness oracle (§6a). From the seam stream alone — independent of the model's flow notion —
    // reconstruct release->acquire handoffs: the last thread to RELEASE a subject is its publisher; a
    // later, cross-thread ACQUIRE of the same subject completes a synchronizes-with edge. Flag any such
    // handoff whose acquire is not model-hooked (WAIT_REACQUIRE) — the wait/notify shape, where a real
    // handoff carries no merge because the internal re-lock bypasses onMonitorEnter. Subjects are
    // retained (debug tool; not pruned).
    private final Map<StaticObject, String> lastPublisher = new ConcurrentHashMap<>();
    private final AtomicInteger alarms = new AtomicInteger();

    /** What this tracer emits. All modes also print the funnel and any oracle alarms. */
    public enum Mode {
        /** Nothing per-seam — only the closing counts (the funnel + the authored/machinery split). */
        SUMMARY,
        /** Every seam as it fires — the full firehose (who / when / what / staged count). */
        TRACE,
        /** Only the meaningful events: lifecycle + cross-thread handoffs, as an ordered narrative. */
        STORY,
        /** Nothing per-seam; only missed-handoff alarms. */
        ORACLE
    }

    private final Mode mode;
    private final EspressoContext context;   // for guest-frame provenance (trace/story)
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
    // authored: the programmer wrote this synchronized (the monitor op is in an application-loaded
    // method). machinery: a boot-loaded library method or pure VM code took the lock (finalizer list,
    // loader CHM, class-init, System.out, ...) — the programmer never wrote it. Split by frame loader.
    private final AtomicInteger machinerySeams = new AtomicInteger();

    // The reduced story (trace mode). A flow-commit records (publisher, count) per subject; a later
    // cross-thread acquire of that subject consumes it into a "B receives N writes from A" line. Lines
    // carry the global seq so report() can print them as one ordered timeline. Publisher==acquirer
    // (a thread re-locking its own monitor, or single-threaded bootstrap) is not a handoff and is
    // dropped, so only genuine inter-thread sharing survives.
    private final Map<StaticObject, String> flowPublisher = new ConcurrentHashMap<>();
    private final Map<StaticObject, Integer> flowCount = new ConcurrentHashMap<>();
    private final List<StoryLine> story = Collections.synchronizedList(new ArrayList<>());

    private record StoryLine(long seq, String text) {
    }

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
            out.println("#   [seam N] <thread>  t+<ms>  OP  staged=<w>  live=<L>  run=<r>  <app|jvm>  <subject>  <guest site>  [FLAGS]");
            out.println("#   thread   the acting thread (main, Thread-1, ...)");
            out.println("#   staged=w w writes sit in the thread's private view (published if OP is a release)");
            out.println("#   live=L   application threads alive (started, not yet ended)");
            out.println("#   run=r    application threads runnable right now (r>=2 => this order could matter)");
            out.println("#   subject  the monitor / thread object;  site  the guest Class.method:line doing it");
            out.println("#   app/jvm  app = the programmer wrote this synchronized (app-loaded frame);");
            out.println("#            jvm = library/VM machinery took the lock (finalizer, loader map, class-init, System.out, ...)");
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
        String me = Thread.currentThread().getName();
        boolean hasSubject = subject != null && !StaticObject.isNull(subject);
        // trace / summary: classify this seam's origin and count the machinery split. authored (app):
        // the monitor op sits in an application-loaded method — the programmer wrote this synchronized.
        // jvm: a boot-loaded library method or VM code took the lock (finalizer, loader map, class-init,
        // System.out, ...) — machinery the programmer never wrote. TRACE also prints the seam line — the
        // full firehose, tagged FLOW if it publishes and * if co-enabled; SUMMARY prints nothing here and
        // only reports the totals at the end.
        if (mode == Mode.TRACE || mode == Mode.SUMMARY) {
            boolean authored = context.getVM().topGuestFrameIsApplication();
            if (!authored) {
                machinerySeams.incrementAndGet();
            }
            if (mode == Mode.TRACE) {
                String tag = (flow ? " FLOW" : "") + (coEnabled ? "*" : "");
                out.printf(java.util.Locale.ROOT, "[seam %4d] %-12s t+%8.1fms %-14s staged=%d live=%d run=%d %-3s %-28s %s%s%n",
                                n, me, millis(), op, pendingWrites, liveApp.get(), runnableApp.get(),
                                authored ? "app" : "jvm", label(subject), site(), tag);
            }
        }
        // Completeness oracle: a release publishes to the subject; a cross-thread acquire that is not
        // model-hooked completes a handoff the model does not carry (§6a). Alarms print in every mode.
        if (op.isRelease() && hasSubject) {
            lastPublisher.put(subject, me);
        } else if (op.isAcquire() && hasSubject) {
            String publisher = lastPublisher.get(subject);
            if (publisher != null && !publisher.equals(me) && !op.isModelHooked()) {
                out.printf("[ORACLE] missed handoff #%d: %s %s %s — published by %s, but this acquire fires no model hook (no merge)%n",
                                alarms.incrementAndGet(), me, op, label(subject), publisher);
            }
        }
        // story: record flow publishes per subject and narrate lifecycle + cross-thread handoffs.
        // Everything else — the thousands of stage=0 / single-thread seams — is silently dropped.
        if (mode == Mode.STORY) {
            if (flow && hasSubject) {
                flowPublisher.put(subject, me);
                flowCount.put(subject, pendingWrites);
            }
            switch (op) {
                case SPAWN -> narrate(n, me + " spawns a thread");
                case THREAD_START -> {
                    String recv = consumeHandoff(subject, me, op, hasSubject);
                    narrate(n, recv == null ? me + " starts" : me + " starts, inheriting " + recv);
                }
                case THREAD_END -> narrate(n, me + " ends");
                case MONITOR_ENTER, WAIT_REACQUIRE -> {
                    String recv = consumeHandoff(subject, me, op, hasSubject);
                    if (recv != null) {
                        narrate(n, me + " receives " + recv);
                    }
                }
                default -> {
                }
            }
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
        // The reduced story, as one ordered timeline.
        if (mode == Mode.STORY) {
            List<StoryLine> lines = new ArrayList<>(story);
            lines.sort((a, b) -> Long.compare(a.seq(), b.seq()));
            for (StoryLine line : lines) {
                out.printf("[story] %s%n", line.text());
            }
        }
        // The funnel, as measured. flowCoEnabled is the alphabet the driver explores; the ratios show
        // how much of the raw seam stream is provably irrelevant to reorder under Sync-as-Share.
        out.printf("[explore] seams: %d traced -> %d co-enabled -> %d flow-commit -> %d flow-commit & co-enabled%n",
                        tracedSeams.get(), coEnabledSeams.get(), flowCommits.get(), flowCommitsCoEnabled.get());
        if (mode == Mode.TRACE || mode == Mode.SUMMARY) {
            int machinery = machinerySeams.get();
            out.printf("[explore] origin: %d authored (app code) / %d machinery (jvm/library)%n",
                            tracedSeams.get() - machinery, machinery);
        }
        if (mode == Mode.ORACLE && alarms.get() == 0) {
            out.println("[explore] no missed handoffs detected");
        }
    }

    private void narrate(long seq, String text) {
        story.add(new StoryLine(seq, String.format(java.util.Locale.ROOT, "t+%8.1fms  %s", millis(), text)));
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

    /**
     * If {@code subject} was most recently flow-published by a <em>different</em> thread, consume that
     * record and return a human phrase like {@code "2 writes from Thread-1 (join)"}; else {@code null}.
     */
    private String consumeHandoff(StaticObject subject, String me, SyncOp op, boolean hasSubject) {
        if (!hasSubject) {
            return null;
        }
        String publisher = flowPublisher.get(subject);
        if (publisher == null || publisher.equals(me)) {
            return null;
        }
        int count = flowCount.getOrDefault(subject, 0);
        flowPublisher.remove(subject);
        flowCount.remove(subject);
        String writes = count == 1 ? "1 write" : count + " writes";
        return writes + " from " + publisher + " (" + via(op, subject) + ")";
    }

    /** How the acquirer synchronized with the publisher, for the story phrasing. */
    private static String via(SyncOp op, StaticObject subject) {
        return switch (op) {
            case THREAD_START -> "spawn";
            case WAIT_REACQUIRE -> isThread(subject) ? "join" : "wait on " + shortLabel(subject);
            default -> "lock " + shortLabel(subject);   // MONITOR_ENTER
        };
    }

    private static boolean isThread(StaticObject subject) {
        return subject.getKlass().getType().toString().endsWith("Thread;");
    }

    /** {@code Ljava/lang/Object;} -> {@code Object@1a2b3c}. */
    private static String shortLabel(StaticObject subject) {
        String type = subject.getKlass().getType().toString();
        int slash = type.lastIndexOf('/');
        int end = type.endsWith(";") ? type.length() - 1 : type.length();
        String simple = type.substring(slash < 0 ? 0 : slash + 1, end);
        return simple + "@" + Integer.toHexString(System.identityHashCode(subject));
    }

    private static String label(StaticObject subject) {
        if (subject == null || StaticObject.isNull(subject)) {
            return "-";
        }
        return subject.getKlass().getType() + "@" + Integer.toHexString(System.identityHashCode(subject));
    }
}
