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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.truffle.espresso.EspressoOptions;
import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.meta.EspressoError;
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
 * and spawn/join are M3. The {@code g} concurrency strategy is selected by
 * {@code --java.MemoryModelHistory} (see {@link GlobalHistory}).
 */
public final class LinearMemoryModel implements MemoryModel {

    private final GlobalHistory global;
    private final EspressoContext context;
    private volatile boolean active = false;

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
        this.global = createHistory(context.getLanguage().getMemoryModelHistoryMode());
        this.collapseMode = context.getLanguage().getMemoryModelCollapseMode();
        this.collapseEvery = Math.max(1, context.getLanguage().getMemoryModelCollapseEvery());
        this.liveViews = collapseMode == EspressoOptions.MemoryModelCollapseMode.OFF ? null : ConcurrentHashMap.newKeySet();
        VersioningStats.arm();
    }

    private boolean collapsing() {
        return collapseMode != EspressoOptions.MemoryModelCollapseMode.OFF;
    }

    /**
     * On a cadence of {@link #collapseEvery} sync pushes, reclaim history older than the collapse
     * threshold {@code T = min} over live threads of their observed base — the furthest-behind live
     * thread. Reading each base lock-free is safe: base only advances, so a stale-low read yields a
     * conservative (too-small) threshold that retains more, never less.
     */
    private void maybeCollapse() {
        if (!collapsing() || (pushes.incrementAndGet() % collapseEvery) != 0) {
            return;
        }
        // Threshold = min base over live threads, EXCLUDING threads at base 0. A base-0 thread has
        // observed no commit: every read resolves to ABSENT (read-through to the heap) regardless of
        // what g holds, so it pins no version and must not clamp the threshold. (Guest runtimes always
        // park idle daemon threads — Reference Handler, Finalizer, Cleaner — at base 0; without this
        // they would peg the threshold at 0 and defeat all reclamation.)
        long threshold = Long.MAX_VALUE;
        long maxBase = Long.MIN_VALUE;
        for (LinearLocalView v : liveViews) {
            long b = v.base();
            if (b > 0 && b < threshold) {
                threshold = b;
            }
            if (b > maxBase) {
                maxBase = b;
            }
        }
        if (COLLAPSE_DEBUG) {
            System.err.println("[collapse-debug] liveViews=" + liveViews.size() + " threshold=" + threshold + " maxBase=" + maxBase + " head=" + global.currentCounter());
        }
        if (threshold != Long.MAX_VALUE) {   // at least one live thread past genesis
            global.collapse(threshold);
        }
    }

    private static GlobalHistory createHistory(EspressoOptions.MemoryModelHistoryMode mode) {
        switch (mode) {
            case RWLOCK:
                return new GlobalVersionStore();
            case WATERMARK:
                return new WatermarkHistory();
            default:
                throw EspressoError.shouldNotReachHere("Unknown memory-model history strategy: " + mode);
        }
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

    private Object read(VersionedLocation loc) {
        LinearLocalView view = currentView();
        Object staged = view.getStaged(loc);
        if (staged != null) {
            return staged;
        }
        // Value visible at this thread's base, or ABSENT (caller reads through to the heap).
        return global.snapshot(loc, view.base());
    }

    private void write(VersionedLocation loc, Object value) {
        currentView().stage(loc, value);
    }

    @Override
    public Object readField(StaticObject holder, Field field) {
        return read(new FieldLocation(holder, field));
    }

    @Override
    public void writeField(StaticObject holder, Field field, Object value) {
        write(new FieldLocation(holder, field), value);
    }

    @Override
    public Object readArray(StaticObject array, int index) {
        return read(new ArrayLocation(array, index));
    }

    @Override
    public void writeArray(StaticObject array, int index, Object value) {
        write(new ArrayLocation(array, index), value);
    }

    @Override
    public void onThreadStart() {
        // Fresh thread with empty staging: pull can never conflict.
        LinearLocalView view = currentView();
        VersionedLocation conflict = global.pull(view);
        assert conflict == null : "starting thread cannot conflict";
    }

    @Override
    public void onThreadEnd() {
        LinearLocalView view = currentView();
        handleConflict(global.push(view), view);
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
        handleConflict(global.push(view), view);
        maybeCollapse();
    }

    @Override
    public void onMonitorEnter(StaticObject lock) {
        // Linear communicates through the single global g, not per-lock, so the lock object is unused.
        LinearLocalView view = currentView();
        handleConflict(global.pull(view), view);
    }

    @Override
    public void onMonitorExit(StaticObject lock) {
        LinearLocalView view = currentView();
        handleConflict(global.push(view), view);
        maybeCollapse();
    }

    @Override
    public void onVolatileRead() {
        // Volatile read = pull: import the field's published history before reading.
        LinearLocalView view = currentView();
        handleConflict(global.pull(view), view);
    }

    @Override
    public void onVolatileWrite() {
        // Volatile write = pull then push (§6.4): import history, then publish this write. The value
        // has already been staged by the caller.
        LinearLocalView view = currentView();
        handleConflict(global.pull(view), view);
        handleConflict(global.push(view), view);
        maybeCollapse();
    }

    private void handleConflict(VersionedLocation conflict, LinearLocalView view) {
        if (conflict == null) {
            return;
        }
        // Record a pending conflict; it is raised as a guest exception at the next safe point (the top
        // of the bytecode loop, past any just-executed monitorexit), or by GuestRunnable if this was
        // the thread's final push. We must NOT throw from inside the monitor op itself: that corrupts
        // the synchronized block's exit machinery (the implicit exception-path monitorexit re-runs).
        view.setPendingConflict("Sync-as-Share conflict: incompatible concurrent update to " + describe(conflict));
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
