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
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * Per-context memory-model service for Sync-as-Share.
 *
 * <p>A {@code MemoryModel} mediates ordinary shared-memory accesses and synchronisation actions.
 * The stock, sequentially-consistent behaviour is provided by {@link SharedMemoryModel}, which is
 * the identity: it is never consulted on the interpreter hot path because the hooks are guarded by
 * {@link com.oracle.truffle.espresso.EspressoLanguage#isMemoryModelEnabled()}, a partial-evaluation
 * constant that is {@code false} under {@code --java.MemoryModel=shared}, so the entire hook folds
 * away and the compiled code is identical to stock Espresso.
 *
 * <p>The Linear version-controlled memory model (paper: Sync-as-Share, &sect;3.1/&sect;4.3) is
 * introduced at milestone M2 and will extend this interface with the ordinary field/array access
 * operations and the synchronisation actions of Table 1 (lock/unlock, spawn/join, start/end,
 * volatile read/write). M1 only establishes the seam and proves the {@code shared} control has zero
 * overhead.
 */
public interface MemoryModel {

    /**
     * Sentinel returned by {@link #readField} when a location has no versioned value visible to the
     * calling thread. The caller then reads through to the real heap (the pre-main heap is the
     * implicit initial committed version). Distinct from every real value: boxed primitives and
     * {@code StaticObject} (incl. {@code StaticObject.NULL}) are never {@code == ABSENT}.
     */
    Object ABSENT = new Object();

    /**
     * Whether this model changes shared-memory semantics. {@code false} for the stock
     * {@link SharedMemoryModel}; {@code true} for version-controlled models. Mirrors
     * {@link com.oracle.truffle.espresso.EspressoLanguage#isMemoryModelEnabled()}.
     */
    boolean isEnabled();

    // --- Ordinary field access (called only when enabled; the hot-path guard folds under shared) ---

    /**
     * Read a field of {@code holder} through the calling thread's view: the staged value, else the
     * value visible at the thread's base version, else {@link #ABSENT} (read through to the heap).
     * The result is boxed for primitives, a {@code StaticObject} for reference fields.
     */
    Object readField(StaticObject holder, Field field);

    /** Stage a write of a field of {@code holder} into the calling thread's view (boxed value). */
    void writeField(StaticObject holder, Field field, Object value);

    // Array elements are intentionally NOT part of the interface: Sync-as-Share does not define array
    // semantics, so versioned array access errors out (see BytecodeNode.arraysUnsupported).

    // --- Synchronisation actions (Table 1). May report a conflict. ---

    void onThreadStart();

    void onThreadEnd();

    /** Thread spawn: the parent publishes its view so the child (which pulls on start) inherits it. */
    void onSpawn();

    void onMonitorEnter();

    void onMonitorExit();

    /** Volatile read = pull (import the field's published history before reading). */
    void onVolatileRead();

    /** Volatile write = pull then push (import history, then publish this write). */
    void onVolatileWrite();

    // --- Activation gate ---

    /**
     * Whether versioning is live. It is <em>off</em> during JDK bootstrap — the pre-main heap is the
     * initial committed version, and versioning it would both be pointless and break VM substitutions
     * that read fields directly off the heap — and flipped on before user {@code main}. The hot-path
     * hooks test {@code isMemoryModelEnabled() && model.isActive()}; the first conjunct is the
     * PE-constant, so the whole hook still folds away under {@code shared}.
     */
    default boolean isActive() {
        return false;
    }

    /** Turn versioning on. Called once, after context bootstrap completes and before user code runs. */
    default void activate() {
    }
}
