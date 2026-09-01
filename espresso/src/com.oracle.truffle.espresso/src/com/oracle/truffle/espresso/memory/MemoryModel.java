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

    /**
     * Sync-as-Share (stage-on-access): a host-array projection of an app-owned array as seen through the
     * acting thread's view — each element is that thread's {@link #readArray staged/committed} value where
     * it has one, else the physical (version-0) element. Returned by {@link StaticObject#unwrap} for
     * app-owned arrays so the native / reflection / {@code arraycopy} READ path operates on the thread's
     * view rather than raw physical. Model-agnostic: {@code readArray} already resolves staged-or-committed
     * for both linear and branching. Materialised on access; native writes back through it are out of
     * scope. Boxing is generic (Integer for int[], StaticObject for reference). See design/view-realisation.md.
     */
    default Object arrayWorkingCopy(StaticObject array, Object physical) {
        int n = java.lang.reflect.Array.getLength(physical);
        Object copy = java.lang.reflect.Array.newInstance(physical.getClass().getComponentType(), n);
        for (int i = 0; i < n; i++) {
            Object v = readArray(array, i);
            java.lang.reflect.Array.set(copy, i, v == ABSENT ? java.lang.reflect.Array.get(physical, i) : v);
        }
        return copy;
    }

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
     * Volatile read (the paper's {@code VolatileRead} rule). A volatile field is an <em>un-versioned</em>
     * cell {@code (value, commit)} kept beside the locks, not in the versioned heap: the value is a plain
     * slot, the commit carries the happens-before of the ordinary writes published alongside it. A read
     * <em>acquires</em> — it pulls (Branching: merges the cell's commit; Linear: pulls {@code g}), which
     * may raise a conflict on those ordinary writes — then returns the cell's value (or {@link #ABSENT}
     * if never written, so the caller reads the heap default). A read publishes nothing.
     */
    Object onVolatileRead(StaticObject holder, Field field);

    /**
     * Volatile write (the paper's {@code VolatileWrite} rule). Acquires as {@link #volatileRead}, then
     * <em>releases</em>: sets the cell's value to {@code value} and publishes the writer's commit to the
     * cell. The value is stored in the cell only — never staged or versioned — so concurrent volatile
     * writes to the same field are not a data race (last writer wins); only the ordinary writes carried
     * on the commit are conflict-checked, at the acquire. Atomic per field.
     */
    void onVolatileWrite(StaticObject holder, Field field, Object value);

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

    // --- Scheduler introspection (read-only; used by the schedule explorer, not the hot path) ---

    /**
     * Number of writes the calling thread has staged but not yet published. A release-type seam
     * (monitor exit, thread end, spawn, wait-release, volatile write) is a <em>flow-commit</em> —
     * a seam that actually publishes something and can therefore change another thread's view — iff
     * this is {@code > 0}. A release with nothing staged commits nothing, so its ordering relative to
     * other threads is provably irrelevant under Sync-as-Share (the isolation property). The schedule
     * explorer uses this to reduce the raw seam stream to the seams worth driving; see
     * {@code design/schedule-explorer.md} &sect;3a. Returns {@code 0} for {@link SharedMemoryModel}
     * and whenever the calling thread has no view yet.
     */
    default int pendingWriteCount() {
        return 0;
    }

    /**
     * A human label for the sync point a monitor op represents, for conflict diagnostics. A monitor op
     * whose subject is a {@code Thread} object <em>is</em> a {@code join} — {@link Thread#join} is
     * implemented as {@code synchronized (thread) { ... wait() ... }}, so it routes through the monitor
     * hooks — and reporting "join" reflects the programmer's intent rather than "lock acquire/release",
     * which is only the mechanism. Any other monitor is an ordinary lock.
     */
    static String monitorSyncLabel(EspressoContext context, StaticObject lock, boolean acquire) {
        if (lock != null && !StaticObject.isNull(lock) && context.getMeta().java_lang_Thread.isAssignableFrom(lock.getKlass())) {
            return "join";
        }
        return acquire ? "lock acquire" : "lock release";
    }
}
