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
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
// FieldLocation / ArrayLocation / LinearLocation are in this package.

/**
 * The Linear version-controlled memory model (paper &sect;3.1/&sect;4.3), ported from the {@code
 * gitmem} prototype's {@code linear::LinearMemoryModel}. All synchronisation actions communicate
 * through a single global history {@link GlobalVersionStore} {@code g}: {@code start}/{@code lock}
 * pull; {@code end}/{@code unlock} push. Ordinary reads/writes operate on the calling thread's
 * {@link LinearLocalView}. A conflict (an unseen update to a location this thread also changed)
 * surfaces at the synchronisation point as a guest exception.
 *
 * <p>Scope (M2): instance + static fields, monitor lock/unlock, thread start/end. Arrays, volatiles,
 * and spawn/join are M3.
 */
public final class LinearMemoryModel implements MemoryModel {

    private final GlobalVersionStore global = new GlobalVersionStore();
    private final EspressoContext context;
    private volatile boolean active = false;

    public LinearMemoryModel(EspressoContext context) {
        this.context = context;
    }

    @Override
    public boolean isEnabled() {
        return true;
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
        return context.getLanguage().getThreadLocalState().getMemoryView();
    }

    private Object read(LinearLocation loc) {
        LinearLocalView view = currentView();
        Object staged = view.getStaged(loc);
        if (staged != null) {
            return staged;
        }
        // Value visible at this thread's base, or ABSENT (caller reads through to the heap).
        return global.snapshot(loc, view.base());
    }

    private void write(LinearLocation loc, Object value) {
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
        LinearLocation conflict = global.pull(view);
        assert conflict == null : "starting thread cannot conflict";
    }

    @Override
    public void onThreadEnd() {
        LinearLocalView view = currentView();
        handleConflict(global.push(view), view);
    }

    @Override
    public void onSpawn() {
        // Parent publishes its view so the child (which pulls on start) inherits the parent's writes.
        LinearLocalView view = currentView();
        handleConflict(global.push(view), view);
    }

    @Override
    public void onMonitorEnter() {
        LinearLocalView view = currentView();
        handleConflict(global.pull(view), view);
    }

    @Override
    public void onMonitorExit() {
        LinearLocalView view = currentView();
        handleConflict(global.push(view), view);
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
    }

    private void handleConflict(LinearLocation conflict, LinearLocalView view) {
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

    private static String describe(LinearLocation loc) {
        if (loc instanceof FieldLocation f) {
            return f.field().getDeclaringKlass().getName() + "." + f.field().getName();
        } else if (loc instanceof ArrayLocation a) {
            return a.array().getKlass().getName() + "[" + a.index() + "]";
        }
        return String.valueOf(loc);
    }
}
