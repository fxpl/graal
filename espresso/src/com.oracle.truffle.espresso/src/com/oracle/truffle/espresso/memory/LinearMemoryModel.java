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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.truffle.espresso.EspressoOptions;
import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
// FieldLocation / ArrayLocation / VersionedLocation are in this package.

/**
 * The Linear version-controlled memory model (paper &sect;3.1/&sect;4.3), ported from the {@code
 * gitmem} prototype's {@code linear::LinearMemoryModel}. All synchronisation actions communicate
 * through a single global history {@link GlobalHistory} {@code g}: {@code start}/{@code lock}
 * pull; {@code end}/{@code unlock} push. Ordinary reads/writes operate on the calling thread's
 * {@link LinearLocalView}. A conflict (an unseen update to a location this thread also changed)
 * surfaces at the synchronisation point as a guest exception.
 *
 * <p>Scope (M2): instance + static fields, monitor lock/unlock, thread start/end. Arrays, volatiles,
 * and spawn/join are M3. The single global history {@code g} is a {@link GlobalVersionStore}.
 */
public final class LinearMemoryModel implements MemoryModel {

    private final GlobalHistory global;
    private final EspressoContext context;
    private volatile boolean active = false;
    // Sync-as-Share (stage-on-access): the shared version-0 cache. The first thread to read a versioned
    // field with no staged/committed value freezes the physical (genesis) value here; every thread then
    // reads that one frozen value instead of the live slot, so v0 is a single fixed version (the paper's
    // root commit) rather than a per-thread capture. Populated once per location via putIfAbsent.
    private final Map<VersionedLocation, Object> genesis = new ConcurrentHashMap<>();
    // Per-volatile-field lock making a volatile read's (pull, get-value) and a volatile write's
    // (pull, set-value, push) atomic per field — the paper's pull-push is one action. Without it a
    // reader could see a new volatile value while its pull missed the ordinary writes published with it
    // (the publication window). The value itself is un-versioned (the physical slot); this lock only
    // couples the value with the g pull/push. Keyed by FieldLocation.
    private final Map<FieldLocation, Object> volatileLocks = new ConcurrentHashMap<>();

    private Object volatileLock(FieldLocation loc) {
        return volatileLocks.computeIfAbsent(loc, k -> new Object());
    }

    // History collapsing (--java.MemoryModelCollapse). When not OFF, every live thread's view is
    // registered here so the collapse trigger can compute the threshold T = min over live bases; and
    // a collapse runs once every {@code collapseEvery} sync pushes. All of this is skipped entirely
    // (no registry, no counter) under OFF, so the default path is unchanged.
    private final EspressoOptions.MemoryModelCollapseMode collapseMode;
    private final int collapseEvery;
    private final Set<LinearLocalView> liveViews;
    private final AtomicLong pushes = new AtomicLong();
    private static final boolean COLLAPSE_DEBUG = System.getenv("SYNCSHARE_COLLAPSE_DEBUG") != null;

    public LinearMemoryModel(EspressoContext context) {
        this.context = context;
        this.global = new GlobalVersionStore();
        this.collapseMode = context.getLanguage().getMemoryModelCollapseMode();
        this.collapseEvery = Math.max(1, context.getLanguage().getMemoryModelCollapseEvery());
        this.liveViews = collapseMode == EspressoOptions.MemoryModelCollapseMode.OFF ? null : ConcurrentHashMap.newKeySet();
        VersioningStats.arm();
    }

    private boolean collapsing() {
        return collapseMode != EspressoOptions.MemoryModelCollapseMode.OFF;
    }

    /**
     * On a cadence of {@link #collapseEvery} sync pushes, reclaim history. Both policies read live
     * bases lock-free, which is safe: a base only advances, so a stale-low read is conservative
     * (retains more, never less). Threads at base 0 are excluded — having observed no commit, every
     * read resolves to ABSENT (heap read-through) regardless of {@code g}, so they pin no version.
     * (Guest runtimes park idle daemon threads — Reference Handler, Finalizer, Cleaner — at base 0;
     * without this they would clamp reclamation to genesis.)
     *
     * <ul>
     *   <li>{@code THRESHOLD}: collapse below {@code T = min} live base — drops the settled tail.</li>
     *   <li>{@code SQUASH}: pass the full live-base set — also drops the interior no base resolves to
     *       (the case a parked-low thread defeats under {@code THRESHOLD}).</li>
     * </ul>
     */
    private void maybeCollapse() {
        if (!collapsing() || (pushes.incrementAndGet() % collapseEvery) != 0) {
            return;
        }
        if (collapseMode == EspressoOptions.MemoryModelCollapseMode.SQUASH) {
            long[] bases = liveBasesPastGenesis();
            if (COLLAPSE_DEBUG) {
                System.err.println("[collapse-debug] squash liveViews=" + liveViews.size() + " bases=" + bases.length + " head=" + global.currentCounter());
            }
            if (bases.length > 0) {
                global.squash(bases);
            }
            return;
        }
        long threshold = Long.MAX_VALUE;
        for (LinearLocalView v : liveViews) {
            long b = v.base();
            if (b > 0 && b < threshold) {
                threshold = b;
            }
        }
        if (COLLAPSE_DEBUG) {
            System.err.println("[collapse-debug] threshold=" + threshold + " liveViews=" + liveViews.size() + " head=" + global.currentCounter());
        }
        if (threshold != Long.MAX_VALUE) {   // at least one live thread past genesis
            global.collapse(threshold);
        }
    }

    /** Distinct-not-required snapshot of live thread bases {@code > 0}, for {@code SQUASH}. */
    private long[] liveBasesPastGenesis() {
        java.util.ArrayList<Long> bases = new java.util.ArrayList<>();
        for (LinearLocalView v : liveViews) {
            long b = v.base();
            if (b > 0) {
                bases.add(b);
            }
        }
        long[] out = new long[bases.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = bases.get(i);
        }
        return out;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public MemoryView newThreadView() {
        LinearLocalView view = new LinearLocalView();
        if (collapsing()) {
            liveViews.add(view);
        }
        return view;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void activate() {
        active = true;
    }

    private LinearLocalView currentView() {
        // Safe: under linear the thread-local view was created by this model's newThreadView().
        return (LinearLocalView) context.getLanguage().getThreadLocalState().getMemoryView();
    }

    @Override
    public int pendingWriteCount() {
        MemoryView v = context.getLanguage().getThreadLocalState().peekMemoryView();
        return v == null ? 0 : ((LinearLocalView) v).stagingSize();
    }

    @Override
    public int acquiredWriteCount() {
        // A monitor acquire or thread start is exactly one pull, so the last pull's count is the answer.
        MemoryView v = context.getLanguage().getThreadLocalState().peekMemoryView();
        return v == null ? 0 : ((LinearLocalView) v).lastPulledWrites();
    }

    private Object read(VersionedLocation loc) {
        LinearLocalView view = currentView();
        Object staged = view.getStaged(loc);
        if (staged != null) {
            return staged;
        }
        // Value visible at this thread's base, or ABSENT. The shared genesis (version-0) cache is consulted
        // by readField after this (fields only), not here — array materialisation also calls read() and must
        // not pick up field-genesis values.
        return global.snapshot(loc, view.base());
    }

    private void write(VersionedLocation loc, Object value) {
        currentView().stage(loc, value);
    }

    // Push/pull wrappers that fold array working-copy writes into staging first (so the conflict check and
    // publication see this thread's array writes at per-index granularity), then drop the working copies
    // (they rebuild from the freshly-observed base on next access). Every sync action goes through these.
    private VersionedLocation pushG(LinearLocalView view) {
        view.flushArrays();
        VersionedLocation conflict = global.push(view);
        view.clearArrays();
        return conflict;
    }

    private VersionedLocation pullG(LinearLocalView view) {
        view.flushArrays();
        VersionedLocation conflict = global.pull(view);
        view.clearArrays();
        return conflict;
    }

    @Override
    public Object readField(StaticObject holder, Field field) {
        FieldLocation loc = new FieldLocation(holder, field);
        Object v = read(loc);
        if (v != MemoryModel.ABSENT) {
            return v;
        }
        // Not staged and no committed version: the shared frozen genesis, else ABSENT (caller reads the
        // slot and captures it here). Committed is checked first (in read()), so it shadows genesis.
        Object g = genesis.get(loc);
        return g != null ? g : MemoryModel.ABSENT;
    }

    @Override
    public void writeField(StaticObject holder, Field field, Object value) {
        write(new FieldLocation(holder, field), value);
    }

    @Override
    public void captureRead(StaticObject holder, Field field, Object value) {
        // First capture wins (putIfAbsent) so v0 is a single value shared by all threads.
        genesis.putIfAbsent(new FieldLocation(holder, field), value);
    }

    @Override
    public Object readArray(StaticObject array, int index) {
        return java.lang.reflect.Array.get(arrayVersionFor(array).host, index);
    }

    @Override
    public void writeArray(StaticObject array, int index, Object value) {
        ArrayVersion av = arrayVersionFor(array);
        java.lang.reflect.Array.set(av.host, index, value);
        av.touched.set(index);
    }

    @Override
    public Object arrayView(StaticObject array) {
        return arrayVersionFor(array).host;
    }

    @Override
    public void markArrayRange(StaticObject array, int destPos, int length) {
        arrayVersionFor(array).touched.set(destPos, destPos + length);
    }

    /**
     * The acting thread's working copy of {@code array}, materialising it on first access: a real host
     * array of the array's component type, seeded from the physical (version-0) array and overlaid with
     * this thread's staged-or-committed elements ({@link #read}, i.e. staged else the value visible at the
     * view's base). Cached on the view and reused until the next push/pull clears it. See {@link
     * ArrayVersion}.
     */
    private ArrayVersion arrayVersionFor(StaticObject array) {
        LinearLocalView view = currentView();
        ArrayVersion av = view.arrayVersion(array);
        if (av == null) {
            Object physical = context.getLanguage().getArrayProperty().getObject(array);
            int n = java.lang.reflect.Array.getLength(physical);
            Object host = java.lang.reflect.Array.newInstance(physical.getClass().getComponentType(), n);
            System.arraycopy(physical, 0, host, 0, n);
            for (int i = 0; i < n; i++) {
                Object v = read(new ArrayLocation(array, i));
                if (v != MemoryModel.ABSENT) {
                    java.lang.reflect.Array.set(host, i, v);
                }
            }
            av = new ArrayVersion(host);
            view.putArrayVersion(array, av);
        }
        return av;
    }

    @Override
    public void onThreadStart() {
        // Fresh thread with empty staging: pull can never conflict.
        LinearLocalView view = currentView();
        VersionedLocation conflict = pullG(view);
        assert conflict == null : "starting thread cannot conflict";
    }

    @Override
    public void onThreadEnd() {
        LinearLocalView view = currentView();
        handleConflict(pushG(view), view, "thread exit");
        if (collapsing()) {
            // Retire this view before collapsing so the ending thread no longer clamps the threshold.
            liveViews.remove(view);
            maybeCollapse();
        }
    }

    @Override
    public void onSpawn(StaticObject childThread) {
        // Parent publishes its view so the child (which pulls on start) inherits the parent's writes.
        // Linear communicates through the single global g, not per-child, so childThread is unused.
        LinearLocalView view = currentView();
        handleConflict(pushG(view), view, "spawn");
        maybeCollapse();
    }

    @Override
    public void onMonitorEnter(StaticObject lock) {
        // Linear communicates through the single global g, not per-lock, so the lock object is unused.
        LinearLocalView view = currentView();
        handleConflict(pullG(view), view, MemoryModel.monitorSyncLabel(context, lock, true));
    }

    @Override
    public void onMonitorExit(StaticObject lock) {
        LinearLocalView view = currentView();
        handleConflict(pushG(view), view, MemoryModel.monitorSyncLabel(context, lock, false));
        maybeCollapse();
    }

    @Override
    public Object onVolatileRead(StaticObject holder, Field field) {
        // Volatile read (paper VolatileRead): acquire by pulling g, then read the field's un-versioned
        // value straight from its physical slot. The value is not in g (volatiles are race-free — their
        // value is not versioned); Linear's single global g is the acquire for every volatile field, so
        // the pull imports/conflict-checks the reader's ordinary staged writes. The pull happens-before
        // the read, and every writer's field.set happens-before its push, so g's lock carries the value.
        if (field == null) {   // class-init acquire (init-state volatile read): merge only, no heap value
            LinearLocalView v = currentView();
            handleConflict(pullG(v), v, "class init");
            return StaticObject.NULL;
        }
        LinearLocalView view = currentView();
        Object value;
        synchronized (volatileLock(new FieldLocation(holder, field))) {
            handleConflict(pullG(view), view, "volatile read");
            value = field.get(holder, true);   // forceVolatile; atomic with the pull (see volatileLocks)
        }
        return value;
    }

    @Override
    public void onVolatileWrite(StaticObject holder, Field field, Object value) {
        // Volatile write (paper VolatileWrite): acquire (pull g), set the un-versioned value in the
        // physical slot, then release (push the ordinary staged writes to g). The value never enters g,
        // so concurrent volatile writes to the same field are not a data race — last writer wins on the
        // slot; only the ordinary writes published alongside are conflict-checked, by the pull/push.
        if (field == null) {   // class-init release (init-state volatile write): publish staged writes, no heap value
            LinearLocalView v = currentView();
            handleConflict(pullG(v), v, "class init");
            handleConflict(pushG(v), v, "class init");
            maybeCollapse();
            return;
        }
        LinearLocalView view = currentView();
        synchronized (volatileLock(new FieldLocation(holder, field))) {
            handleConflict(pullG(view), view, "volatile write (acquire)");
            field.set(holder, value, true);   // forceVolatile; un-versioned value in the real heap
            handleConflict(pushG(view), view, "volatile write (release)");
        }
        maybeCollapse();
    }

    private void handleConflict(VersionedLocation conflict, LinearLocalView view, String at) {
        if (conflict == null) {
            return;
        }
        // Record a pending conflict; it is raised as a guest exception at the next safe point (the top
        // of the bytecode loop, past any just-executed monitorexit), or by GuestRunnable if this was
        // the thread's final push. We must NOT throw from inside the monitor op itself: that corrupts
        // the synchronized block's exit machinery (the implicit exception-path monitorexit re-runs).
        // `at` names the synchronization point that caught it, plus the guest site, for diagnosis.
        view.setPendingConflict("Sync-as-Share conflict: incompatible concurrent update to " + describe(conflict)
                        + ", caught at " + at + " in " + context.getVM().topGuestSite());
        // Recover the view so it stays consistent if the conflict is caught and execution continues.
        view.clearStaging();
        view.advanceBase(global.currentCounter());
    }

    private static String describe(VersionedLocation loc) {
        if (loc instanceof FieldLocation f) {
            return f.field().getDeclaringKlass().getName() + "." + f.field().getName();
        } else if (loc instanceof ArrayLocation a) {
            return a.array().getKlass().getName() + "[" + a.index() + "]";
        }
        return String.valueOf(loc);
    }
}
