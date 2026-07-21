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

    /**
     * Create this model's per-thread {@link MemoryView}. Called lazily by
     * {@code EspressoThreadLocalState} on first use, so the view type matches the active model
     * (Linear's staging+base view, Branching's staging+DAG-head view). Never called under
     * {@link SharedMemoryModel} (the hot-path hooks fold away, so no thread ever asks for a view).
     */
    MemoryView newThreadView();

    // --- Ordinary field access (called only when enabled; the hot-path guard folds under shared) ---

    /**
     * Read a field of {@code holder} through the calling thread's view: the staged value, else the
     * value visible at the thread's base version, else {@link #ABSENT} (read through to the heap).
     * The result is boxed for primitives, a {@code StaticObject} for reference fields.
     */
    Object readField(StaticObject holder, Field field);

    /** Stage a write of a field of {@code holder} into the calling thread's view (boxed value). */
    void writeField(StaticObject holder, Field field, Object value);

    /** As {@link #readField} but for an array element (per-index): staged, else base snapshot, else {@link #ABSENT}. */
    Object readArray(StaticObject array, int index);

    /** As {@link #writeField} but for an array element (per-index). Bounds/ArrayStore checks are the caller's job. */
    void writeArray(StaticObject array, int index, Object value);

    // --- Synchronisation actions (Table 1). May report a conflict. ---

    void onThreadStart();

    void onThreadEnd();

    /**
     * Thread spawn (runs on the parent). Linear: the parent publishes its view so the child (which
     * pulls on start) inherits it. Branching: the parent commits and records a fork point that the
     * {@code childThread} adopts at its start. {@code childThread} is the child's guest {@code Thread}
     * object; Linear ignores it.
     */
    void onSpawn(StaticObject childThread);

    /**
     * Monitor acquire. Linear: pull. Branching: merge the lock's published branch. {@code lock} is the
     * monitor object (Branching keys per-lock state by it); Linear ignores it.
     */
    void onMonitorEnter(StaticObject lock);

    /** Monitor release. Linear: push. Branching: republish the holder's tip to {@code lock}'s state. */
    void onMonitorExit(StaticObject lock);

    /**
     * Volatile read = pull (import the field's published history before reading). {@code holder}/{@code
     * field} identify the volatile location; Branching keys the per-field synchronisation tip by it,
     * Linear ignores it (the history is the single global {@code g}). §6.4 / Table 2 {@code ReadVolatile}.
     */
    void onVolatileRead(StaticObject holder, Field field);

    /**
     * Volatile write = pull then push (import the field's history, then publish this write) — §6.4 /
     * Table 2 {@code WriteVolatile}. The pull-before-push accumulates the field's release chain and
     * orders concurrent volatile writes so they are not a data race. The staged value must be committed
     * <em>after</em> the pull so it is ordered after the imported history.
     */
    void onVolatileWrite(StaticObject holder, Field field);

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
