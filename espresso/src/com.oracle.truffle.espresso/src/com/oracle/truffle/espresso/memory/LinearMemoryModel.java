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

    @Override
    public Object readField(StaticObject holder, Field field) {
        LinearLocalView view = currentView();
        LinearLocation loc = new LinearLocation(holder, field);
        Object staged = view.getStaged(loc);
        if (staged != null) {
            return staged;
        }
        // Value visible at this thread's base, or ABSENT (caller reads through to the heap).
        return global.snapshot(loc, view.base());
    }

    @Override
    public void writeField(StaticObject holder, Field field, Object value) {
        currentView().stage(new LinearLocation(holder, field), value);
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
    public void onMonitorEnter() {
        LinearLocalView view = currentView();
        handleConflict(global.pull(view), view);
    }

    @Override
    public void onMonitorExit() {
        LinearLocalView view = currentView();
        handleConflict(global.push(view), view);
    }

    private void handleConflict(LinearLocation conflict, LinearLocalView view) {
        if (conflict == null) {
            return;
        }
        // M2: report the conflict and recover so the VM stays stable. We deliberately do NOT throw
        // here: raising a guest exception from inside a monitor operation corrupts the synchronized
        // block's exit machinery (the implicit exception-path monitorexit re-runs and re-conflicts).
        // M4 will introduce a catchable SyncConflictError raised at a safe point instead. Recovery
        // drops the conflicting staged writes and resynchronises the view.
        context.getLogger().warning("Sync-as-Share conflict: incompatible concurrent update to " +
                        conflict.field().getDeclaringKlass().getName() + "." + conflict.field().getName());
        view.clearStaging();
        view.advanceBase(global.currentCounter());
    }
}
