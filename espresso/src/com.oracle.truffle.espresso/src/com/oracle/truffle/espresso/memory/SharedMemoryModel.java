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
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * The stock, sequentially-consistent shared-memory model (the identity). This is the default and is
 * never consulted on the interpreter hot path: the Sync-as-Share hooks are guarded by the
 * partial-evaluation constant {@code EspressoLanguage.isMemoryModelEnabled()}, which is
 * {@code false} for this model, so the hooks fold away entirely and the compiled code matches stock
 * Espresso.
 */
public final class SharedMemoryModel implements MemoryModel {

    public static final SharedMemoryModel INSTANCE = new SharedMemoryModel();

    private SharedMemoryModel() {
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    // None of the following are ever reached: the hot-path hooks are guarded by the PE-constant
    // EspressoLanguage.isMemoryModelEnabled() (false for shared), so they fold away before any call.

    @Override
    public MemoryView newThreadView() {
        throw EspressoError.shouldNotReachHere("SharedMemoryModel.newThreadView: no per-thread view under shared");
    }

    @Override
    public Object readField(StaticObject holder, Field field) {
        throw EspressoError.shouldNotReachHere("SharedMemoryModel.readField: memory-model hooks must be folded under shared");
    }

    @Override
    public void writeField(StaticObject holder, Field field, Object value) {
        throw EspressoError.shouldNotReachHere("SharedMemoryModel.writeField: memory-model hooks must be folded under shared");
    }

    @Override
    public Object readArray(StaticObject array, int index) {
        throw EspressoError.shouldNotReachHere("SharedMemoryModel.readArray: memory-model hooks must be folded under shared");
    }

    @Override
    public void writeArray(StaticObject array, int index, Object value) {
        throw EspressoError.shouldNotReachHere("SharedMemoryModel.writeArray: memory-model hooks must be folded under shared");
    }

    @Override
    public void onThreadStart() {
        throw EspressoError.shouldNotReachHere("SharedMemoryModel.onThreadStart: memory-model hooks must be folded under shared");
    }

    @Override
    public void onThreadEnd() {
        throw EspressoError.shouldNotReachHere("SharedMemoryModel.onThreadEnd: memory-model hooks must be folded under shared");
    }

    @Override
    public void onSpawn(StaticObject childThread) {
        throw EspressoError.shouldNotReachHere("SharedMemoryModel.onSpawn: memory-model hooks must be folded under shared");
    }

    @Override
    public void onMonitorEnter(StaticObject lock) {
        throw EspressoError.shouldNotReachHere("SharedMemoryModel.onMonitorEnter: memory-model hooks must be folded under shared");
    }

    @Override
    public void onMonitorExit(StaticObject lock) {
        throw EspressoError.shouldNotReachHere("SharedMemoryModel.onMonitorExit: memory-model hooks must be folded under shared");
    }

    @Override
    public void onVolatileRead(StaticObject holder, Field field) {
        throw EspressoError.shouldNotReachHere("SharedMemoryModel.onVolatileRead: memory-model hooks must be folded under shared");
    }

    @Override
    public void onVolatileWrite(StaticObject holder, Field field) {
        throw EspressoError.shouldNotReachHere("SharedMemoryModel.onVolatileWrite: memory-model hooks must be folded under shared");
    }
}
