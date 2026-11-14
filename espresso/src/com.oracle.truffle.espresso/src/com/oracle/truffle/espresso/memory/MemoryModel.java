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
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * Per-context memory-model service for Sync-as-Share.
 *
 * <p>A {@code MemoryModel} mediates ordinary shared-memory accesses and synchronisation actions.
 * The stock, sequentially-consistent behaviour is provided by {@link SharedMemoryModel}, which is
 * the identity: it is never consulted on the interpreter hot path because the hooks are guarded by
 * {@link com.oracle.truffle.espresso.EspressoLanguage#isVersionedMemorySelected()}, a partial-evaluation
 * constant that is {@code false} under {@code --java.MemoryModel=shared}, so the entire hook folds
 * away and the compiled code is identical to stock Espresso.
 *
 * <p>The versioned models, {@link LinearMemoryModel} and {@link BranchingMemoryModel} (paper:
 * Sync-as-Share), implement the ordinary field and array accesses and the synchronisation actions of
 * Table 1 (lock/unlock, spawn/join, start/end, volatile read/write, class init).
 */
public interface MemoryModel {

    /**
     * Whether this model changes shared-memory semantics. {@code false} for the stock
     * {@link SharedMemoryModel}; {@code true} for version-controlled models. Mirrors
     * {@link com.oracle.truffle.espresso.EspressoLanguage#isVersionedMemorySelected()}.
     */
    boolean isEnabled();

    /**
     * Create this model's per-thread {@link MemoryView}. Called lazily by
     * {@code EspressoThreadLocalState} on first use, so the view type matches the active model
     * (Linear's staging+base view, Branching's staging+DAG-head view). Never called under
     * {@link SharedMemoryModel} (the hot-path hooks fold away, so no thread ever asks for a view).
     */
    MemoryView newThreadView();

    /**
     * Has a synchronisation action on {@code view}'s thread found a conflict that is still waiting to be
     * raised? Asked at a safe point (the top of the bytecode loop): a conflict cannot be thrown from
     * inside the action, as that corrupts a synchronized block's exit.
     */
    boolean hasPendingConflict(MemoryView view);

    /** The conflict waiting on {@code view}'s thread, which is cleared; called just before raising it. */
    ConflictReport takePendingConflict(MemoryView view);

    // --- Ordinary field access (called only when enabled; the hot-path guard folds under shared) ---

    /**
     * Read a field of {@code holder} through the calling thread's view: the staged value, else the
     * value visible at the thread's base version, else the field's version 0 (its raw slot, which is
     * never written once versioned). The result is boxed for primitives, a {@code StaticObject} for
     * reference fields.
     */
    Object readField(StaticObject holder, Field field);

    /** Stage a write of a field of {@code holder} into the calling thread's view (boxed value). */
    void writeField(StaticObject holder, Field field, Object value);

    // --- Array access (called only for versioned arrays) ---

    /**
     * Declare that the caller is about to read elements [from, to) of {@code array}, and get the host
     * array to read them through. The result has the guest array's host type ({@code int[]} for a guest
     * {@code int[]}, {@code StaticObject[]} for a reference array) and length, and is indexed with the
     * guest indices. What it is depends on the model: the caller must not assume it is the physical array.
     *
     * <p>Contract:
     * <ul>
     * <li>The caller has already checked {@code 0 <= from <= to <= length}; the model does not.</li>
     * <li>The caller reads only [from, to), only for the duration of the operation, and does not keep the
     * result.</li>
     * <li>The caller does not write through the result; a write must be declared with
     * {@link #arrayForWrite}.</li>
     * <li>{@code from == to} is the empty range and is allowed.</li>
     * <li>Volatile, compare-and-set and other atomic accesses are synchronisation actions and must not
     * use this method.</li>
     * </ul>
     */
    Object arrayForRead(StaticObject array, int from, int to);

    /**
     * Declare that the caller is about to write elements [from, to) of {@code array}, and get the host
     * array to write them through (and to read any element in [from, to)). The model records every element
     * of the range as written, whether or not the caller changes its value.
     *
     * <p>Contract, as {@link #arrayForRead}, plus:
     * <ul>
     * <li>The range must be what the caller <em>will</em> write. A range larger than the write is safe
     * but may report false conflicts; a write outside the range is silently lost to other threads.</li>
     * <li>Declare the range only once the write is certain: after bounds, store and other checks that
     * could abandon it.</li>
     * </ul>
     */
    Object arrayForWrite(StaticObject array, int from, int to);

    /** {@link #arrayForRead(StaticObject, int, int)} for the single element {@code index}. */
    default Object arrayForRead(StaticObject array, int index) {
        return arrayForRead(array, index, index + 1);
    }

    /** {@link #arrayForWrite(StaticObject, int, int)} for the single element {@code index}. */
    default Object arrayForWrite(StaticObject array, int index) {
        return arrayForWrite(array, index, index + 1);
    }

    /**
     * The storage a volatile or atomic access reads element {@code index} of the shared {@code array}
     * from, through the view when the model versions the array, else the array itself.
     */
    default Object volatileArrayForRead(StaticObject array, int index) {
        if (Versioning.isVersioned(array)) {
            return arrayForRead(array, index);
        } else {
            return array.getRawArray(array.getKlass().getLanguage());
        }
    }

    /** As {@link #volatileArrayForRead}, for a write. */
    default Object volatileArrayForWrite(StaticObject array, int index) {
        if (Versioning.isVersioned(array)) {
            return arrayForWrite(array, index);
        } else {
            return array.getRawArray(array.getKlass().getLanguage());
        }
    }

    // --- Synchronisation actions (Table 1). May report a conflict. ---

    /** The child's first action, on the child, before its code runs (the parent's half is {@link #onSpawn}). */
    void onThreadBegin();

    void onThreadEnd();

    /**
     * Join (paper Join rule): the current thread has just detected that {@code joined} terminated
     * ({@code Thread.isAlive()} returned {@code false}; {@code join} loops on it). This is the point
     * the joined thread's final action synchronizes-with (JLS 17.4.4). A thread action, not a monitor
     * action. Default: nothing (linear's monitor operations already pull the global history).
     */
    default void onJoin(StaticObject joined) {
    }

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

    /** Monitor release. Linear: push to the global history. Branching: push the holder's head to {@code lock}'s state. */
    void onMonitorExit(StaticObject lock);

    /**
     * Volatile read (the paper's {@code VolatileRead} rule) of {@code field} of the shared {@code holder}.
     * Pulls from the field's volatile sync state (Branching: merges its commit; Linear: pulls the global
     * history), which may raise a conflict, then reads the value: through the view when the model versions
     * the field, else from the raw slot. Pushes nothing. Atomic per field. Boxed for primitives.
     */
    Object onVolatileRead(StaticObject holder, Field field);

    /**
     * Volatile write (the paper's {@code VolatileWrite} rule) of {@code field} of the shared {@code holder}.
     * Writes the value, then pulls from and pushes to the field's volatile sync state. A versioned field's
     * value is staged, so a volatile write unordered with ours is a conflict at the pull (the JLS counts
     * two volatile writes without happens-before as a data race). An unversioned field's value goes to the
     * raw slot and is not staged, so only the other writes the pull brings in are checked. Atomic per field.
     */
    void onVolatileWrite(StaticObject holder, Field field, Object value);

    /**
     * Compare-and-set of {@code field} of the shared {@code holder}, atomic on the field's volatile sync
     * state: pull, read the field, and if it matches {@code expected} (as {@link Field#isSameValue}) write
     * {@code value} and push. The value goes through the view when the model versions the field, else the
     * raw slot. The read comes before the write, so each compare-and-set is informed by the last and two
     * never conflict. A failed one is a volatile read. Values are boxed for primitives.
     */
    boolean onCompareAndSet(StaticObject holder, Field field, Object expected, Object value);

    /**
     * Compare-and-exchange of {@code field} of the shared {@code holder}: as
     * {@link #onCompareAndSet(StaticObject, Field, Object, Object)}, returning the value it compared
     * against rather than whether it matched.
     */
    Object onCompareAndExchange(StaticObject holder, Field field, Object expected, Object value);

    /**
     * Get-and-set of {@code field} of the shared {@code holder}: a compare-and-exchange that always matches,
     * so it writes {@code value} and returns the value it replaced.
     */
    Object onGetAndSet(StaticObject holder, Field field, Object value);

    /**
     * A volatile read of element {@code index} of the shared {@code array}, as {@link #onVolatileRead}
     * on the array's one volatile sync state, which all its elements share. The value is boxed for
     * primitives.
     */
    Object onVolatileArrayRead(StaticObject array, int index);

    /** A volatile write of element {@code index} of the shared {@code array}, as {@link #onVolatileWrite}. */
    void onVolatileArrayWrite(StaticObject array, int index, Object value);

    /**
     * Compare-and-set of element {@code index} of the shared reference {@code array}, atomic on the
     * array's volatile sync state: pull, then if the element is {@code expected} (by identity), write
     * {@code value} and push. The element is read and written in {@link #volatileArrayForRead} and
     * {@link #volatileArrayForWrite}'s storage. The read comes before the write, so each
     * compare-and-set is informed by the last and two never conflict. A failed one is a volatile read.
     */
    boolean onArrayCompareAndSet(StaticObject array, int index, StaticObject expected, StaticObject value);

    /** {@link #onArrayCompareAndSet(StaticObject, int, StaticObject, StaticObject)} for an {@code int} array. */
    boolean onArrayCompareAndSet(StaticObject array, int index, int expected, int value);

    /** {@link #onArrayCompareAndSet(StaticObject, int, StaticObject, StaticObject)} for a {@code long} array. */
    boolean onArrayCompareAndSet(StaticObject array, int index, long expected, long value);

    /**
     * Compare-and-exchange of element {@code index} of the shared reference {@code array}: as
     * {@link #onArrayCompareAndSet(StaticObject, int, StaticObject, StaticObject)}, returning the element it
     * compared against rather than whether it matched.
     */
    StaticObject onArrayCompareAndExchange(StaticObject array, int index, StaticObject expected, StaticObject value);

    /** {@link #onArrayCompareAndExchange(StaticObject, int, StaticObject, StaticObject)} for an {@code int} array. */
    int onArrayCompareAndExchange(StaticObject array, int index, int expected, int value);

    /** {@link #onArrayCompareAndExchange(StaticObject, int, StaticObject, StaticObject)} for a {@code long} array. */
    long onArrayCompareAndExchange(StaticObject array, int index, long expected, long value);

    /** {@link #onArrayCompareAndExchange(StaticObject, int, StaticObject, StaticObject)} for a {@code byte} array. */
    byte onArrayCompareAndExchange(StaticObject array, int index, byte expected, byte value);

    /** {@link #onArrayCompareAndExchange(StaticObject, int, StaticObject, StaticObject)} for a {@code short} array. */
    short onArrayCompareAndExchange(StaticObject array, int index, short expected, short value);

    /**
     * Get-and-set of element {@code index} of the shared reference {@code array}: a compare-and-exchange
     * that always matches, so it writes {@code value} and returns the element it replaced.
     */
    StaticObject onArrayGetAndSet(StaticObject array, int index, StaticObject value);

    /**
     * Class-init release (JLS §12.4.2): {@code <clinit>} of {@code klass} has completed, so push the
     * initializer's writes to the class's init state. Happens once per class.
     */
    void onClassInitialized(ObjectKlass klass);

    /**
     * Class-init acquire: the current thread's first use of {@code klass}, so pull what its initializer
     * pushed. Pushes nothing: a use-to-use edge would invent ordering the JVM never creates and mask
     * real races.
     */
    void onClassInitUse(ObjectKlass klass);

    // --- Activation gate ---

    /**
     * Whether versioning is live. It is <em>off</em> during JDK bootstrap — the pre-main heap is the
     * initial committed version, and versioning it would both be pointless and break VM substitutions
     * that read fields directly off the heap — and flipped on before user {@code main}. Hooks test
     * {@code EspressoContext.isVersionedMemoryEnabled()}, which checks the PE-constant selection first,
     * so the whole hook still folds away under {@code shared}.
     */
    default boolean isActive() {
        return false;
    }

    /**
     * The model's one host {@link java.lang.ref.Cleaner}: runs an action once a guest object is collected,
     * so the model can release what it held for that object. Only models that track object death
     * provide one.
     */
    default java.lang.ref.Cleaner getCleaner() {
        throw new UnsupportedOperationException("this memory model does not track object death");
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
     * The number of writes the calling thread's most recent acquire ({@link #onMonitorEnter} or
     * {@link #onThreadBegin}) made visible to it: the total of the released writes of every release that
     * acquire brought in. At thread start that is everything the parent could see (branching) or
     * everything in the global history (linear).
     * Read by the schedule explorer right after the acquire, for its {@code ACQUIRED(n)} tag. Returns
     * {@code 0} for {@link SharedMemoryModel} and whenever the calling thread has no view yet.
     */
    default int acquiredWriteCount() {
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
