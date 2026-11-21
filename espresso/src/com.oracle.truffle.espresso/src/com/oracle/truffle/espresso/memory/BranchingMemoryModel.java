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

import java.lang.ref.Cleaner;
import java.lang.reflect.Array;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * The Branching version-controlled memory model (paper: Sync-as-Share, the branching model). This is
 * the <b>eager</b> policy, so a conflict is detected <em>at the merge</em> (the synchronisation point),
 * and ordinary reads never conflict.
 *
 * <p>Unlike {@link LinearMemoryModel}, there is no single global history: communication is pairwise,
 * through sync states. Each thread works on a {@link BranchingView}, and a {@link BranchingRepresentation}
 * keeps the versions the views and sync states hold, today as a commit DAG ({@link DagRepresentation}).
 * Every synchronisation action is a <b>pull</b> (merge in what another sync state holds) and/or a
 * <b>push</b> (hand what this thread holds to another sync state):
 * <ul>
 * <li><b>spawn</b>: push to the child thread's sync state;</li>
 * <li><b>join</b>: pull from the joined thread's sync state;</li>
 * <li><b>monitor enter / exit</b>: pull from / push to the lock's sync state;</li>
 * <li><b>volatile read / write</b>: pull / pull then push, on the field's sync state;</li>
 * <li><b>class init</b>: pull then push on initialisation, pull on first use;</li>
 * <li><b>thread end</b>: end, so the thread's own state holds what a join pulls.</li>
 * </ul>
 *
 * <p>Sync states live on their owners: a thread's in a hidden field on the {@code Thread}, lock and
 * volatile states in the object's {@link ObjectSyncState}, class-init state on the {@link ObjectKlass}.
 * A {@link java.lang.ref.Cleaner} tells the representation when an object or a thread dies.
 */
public final class BranchingMemoryModel implements MemoryModel {

    private final EspressoContext context;
    private volatile boolean active = false;

    // how the versions are kept, told of the deaths and the end of the run
    private final BranchingRepresentation representation;

    // the id of the next thread's view
    private final AtomicInteger nextThreadId = new AtomicInteger();

    // This is a record so that we don't accidentally capture the StaticObject
    // if we did that, we could end up keeping a reference to the StaticObject in the cleaner
    // register set and then the object could never be GC'd
    record MarkDead(ObjectNumber objectNumber, BranchingRepresentation representation) implements Runnable {

        @Override
        public void run() {
            objectNumber.setDead();
            representation.onObjectDied(objectNumber);
            ModelEvents.markedForPurging();
        }
    }

    /* A cleaner to purge out object numbers that represent objects GC'd */
    private final Cleaner cleaner = Cleaner.create();

    @Override
    public Cleaner getCleaner() {
        return cleaner;
    }

    private void registerForCleaning(StaticObject object) {
        if (!object.isCleanerRegistered()) {
            object.markCleanerRegistered();
            cleaner.register(object, new MarkDead(object.getObjectNumber(), representation));
            ModelEvents.cleanerRegistered();
        }
    }

    private BranchingMemoryModel(EspressoContext context, BranchingRepresentation representation) {
        this.context = context;
        this.representation = representation;
    }

    /** The branching model with its versions kept as a commit DAG. */
    public static BranchingMemoryModel withDag(EspressoContext context, ClockFactory clocks) {
        return new BranchingMemoryModel(context, new DagRepresentation(clocks));
    }

    /** The branching model with its versions kept as snapshots, which always keep vector clocks. */
    public static BranchingMemoryModel withSnapshots(EspressoContext context) {
        return new BranchingMemoryModel(context, new SnapshotRepresentation());
    }

    @Override
    public void dispose() {
        representation.dispose(cleaner);
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    public MemoryView newThreadView() {
        return representation.newView(this::bindThreadSyncState, nextThreadId.getAndIncrement());
    }

    @Override
    public SyncState<?> newSyncState(SyncState.Kind kind) {
        return representation.newSyncState(kind);
    }

    private SyncState<?> newThreadSyncState(StaticObject thread) {
        SyncState<?> state = newSyncState(SyncState.Kind.THREAD);
        VersioningStats.describeThread(state, thread);

        // store the state on the thread object itself
        context.getMeta().java_lang_Thread_0syncState.setHiddenObject(thread, state);

        // once the thread object is collected nothing can join it, so its state lets go of what it holds
        cleaner.register(thread, state::release);
        return state;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void activate() {
        active = true;
        // this thread goes on to run main, which never passes through onThreadBegin
        currentView().activate();
    }

    private BranchingView currentView() {
        // Safe: under branching the thread-local view was created by this model's newThreadView().
        return (BranchingView) context.getLanguage().getThreadLocalState().getMemoryView();
    }

    /**
     * Binds a view to its thread's sync state, the first time the view needs it (on the view's own
     * thread, after activation). A spawned thread finds the state its parent pushed into at spawn; main,
     * or a thread spawned before versioning was on, gets a fresh one attached to its Thread.
     */
    private SyncState<?> bindThreadSyncState() {
        StaticObject thread = context.getCurrentPlatformThread();
        assert thread != null : "binding a view before its thread is registered";

        Object existing = context.getMeta().java_lang_Thread_0syncState.getHiddenObject(thread);
        if (existing instanceof SyncState<?> s) {
            return s;
        }
        return newThreadSyncState(thread);
    }

    @Override
    public int pendingWriteCount() {
        MemoryView v = context.getLanguage().getThreadLocalState().peekMemoryView();
        return v == null ? 0 : ((BranchingView) v).pendingWriteCount();
    }

    // --- Ordinary field / array access: stage and read on the calling thread's view. ---

    @Override
    public Object readField(StaticObject holder, Field field) {
        if (holder.hasVersionedWrite()) {
            return currentView().readField(holder, field);
        }
        // no thread has written the object, so every field is at version 0, its raw slot
        return field.getRawValue(holder);
    }

    @Override
    public void writeField(StaticObject holder, Field field, Object value) {
        currentView().writeField(holder, field, value);
        holder.markHasVersionedWrite();
        registerForCleaning(holder);
    }

    // a volatile's value, through the view when the model owns the field, else from the raw slot
    private Object readVolatileField(StaticObject holder, Field field) {
        if (Versioning.versionsField(holder, field)) {
            return readField(holder, field);
        } else {
            return field.getRawValue(holder);
        }
    }

    // as readVolatileField, for a write
    private void writeVolatileField(StaticObject holder, Field field, Object value) {
        if (Versioning.versionsField(holder, field)) {
            writeField(holder, field, value);
        } else {
            field.setRawValue(holder, value);
        }
    }

    @Override
    public Object arrayForRead(StaticObject array, int from, int to) {
        return currentView().arrayForRead(array, from, to);
    }

    @Override
    public Object arrayForWrite(StaticObject array, int from, int to) {
        Object storage = currentView().arrayForWrite(array, from, to);
        array.markHasVersionedWrite();
        registerForCleaning(array);
        return storage;
    }

    // --- Synchronisation actions (Table 1). May report a conflict at the merge. ---

    @Override
    public void onThreadBegin() {
        // the parent put the commit in the thread sync state slot, so the view only needs activating
        currentView().activate();
    }

    @Override
    public void onThreadEnd() {
        // the thread's own state takes its last writes for a join to pull, before the terminating notify
        // wakes any joiner, and the representation lets go of them once the thread object is collected
        currentView().end();
    }

    @Override
    public void onJoin(StaticObject joined) {
        // This thread detected that `joined` terminated, so pull the final
        // commit the joined thread stored on its Thread object. Absent if it never committed anything.
        Object finalSyncState = context.getMeta().java_lang_Thread_0syncState.getHiddenObject(joined);
        if (!(finalSyncState instanceof SyncState<?> state)) {
            return;
        }
        BranchingView view = currentView();
        handleConflicts(view.pull(state), view, "join");
    }

    @Override
    public void onSpawn(StaticObject childThread) {
        // Fork: the parent pushes to the child, which pulls it at start. Runs on the parent, before the
        // child begins. First mark what the child can now reach, so the push carries it.
        Versioning.onSpawn(childThread);
        BranchingView view = currentView();
        SyncState<?> childState = newThreadSyncState(childThread);
        view.push(childState);
    }

    @Override
    public void onMonitorEnter(StaticObject lock) {
        // pull from the lock
        SyncState<?> state = lock.lockSyncState();
        BranchingView view = currentView();
        handleConflicts(view.pull(state), view, MemoryModel.monitorSyncLabel(context, lock, true));
    }

    @Override
    public void onMonitorExit(StaticObject lock) {
        // push to the lock
        BranchingView view = currentView();
        SyncState<?> state = lock.lockSyncState();
        view.push(state);
    }

    @Override
    public Object onVolatileRead(StaticObject holder, Field field) {
        // Volatile read (paper VolatileRead): pull from the field's sync state, then read the value
        // (readVolatileField). No push — a read adds no history. Atomic per
        // field (synchronised on the sync state) so value and commit are read together, consistent
        // against a concurrent volatile write to the same field.
        BranchingView view = currentView();
        SyncState<?> state = holder.volatileSyncState(field);
        ConflictingLocations conflict;
        Object value;
        synchronized (state) {
            conflict = view.pull(state);
            value = readVolatileField(holder, field);
        }
        handleConflicts(conflict, view, "volatile read");
        return value;
    }

    @Override
    public void onVolatileWrite(StaticObject holder, Field field, Object value) {
        // Volatile write (paper VolatileWrite): write the value (writeVolatileField, staged when the field is
        // versioned), then release it to the field's sync state. The release merges what the state already
        // holds (a push alone would drop it), and staging first means a volatile write unordered with ours
        // is a conflict there: the JLS counts two volatile writes without happens-before as a data race.
        // Atomic per field.
        BranchingView view = currentView();
        SyncState<?> state = holder.volatileSyncState(field);
        ConflictingLocations conflicts;
        synchronized (state) {
            writeVolatileField(holder, field, value);
            conflicts = view.pull(state);
            if (conflicts.isEmpty()) {
                view.push(state);
            }
        }
        handleConflicts(conflicts, view, "volatile write");
    }

    @Override
    public boolean onCompareAndSet(StaticObject holder, Field field, Object expected, Object value) {
        return compareAndSet(holder.volatileSyncState(field), () -> field.isSameValue(readVolatileField(holder, field), expected), () -> writeVolatileField(holder, field, value));
    }

    @Override
    public Object onCompareAndExchange(StaticObject holder, Field field, Object expected, Object value) {
        return compareAndExchange(holder.volatileSyncState(field), () -> readVolatileField(holder, field), witness -> field.isSameValue(witness, expected),
                        () -> writeVolatileField(holder, field, value));
    }

    @Override
    public Object onGetAndSet(StaticObject holder, Field field, Object value) {
        return compareAndExchange(holder.volatileSyncState(field), () -> readVolatileField(holder, field), witness -> true, () -> writeVolatileField(holder, field, value));
    }

    // pull, test, then write and push, all atomic on the location's sync state; the read comes first, so
    // the write is informed by the last one. matches reads the location after the pull, typed by the caller.
    private boolean compareAndSet(SyncState<?> state,BooleanSupplier matches, Runnable write) {
        BranchingView view = currentView();
        ConflictingLocations conflicts;
        boolean swapped;
        synchronized (state) {
            conflicts = view.pull(state);
            swapped = matches.getAsBoolean();
            if (swapped && conflicts.isEmpty()) {
                write.run();
                view.push(state);
            }
        }
        handleConflicts(conflicts, view, "compare-and-set");
        return swapped;
    }

    // as compareAndSet, returning the value read rather than whether it matched
    private <T> T compareAndExchange(SyncState<?> state,Supplier<T> read, Predicate<T> matches, Runnable write) {
        BranchingView view = currentView();
        ConflictingLocations conflicts;
        T witness;
        synchronized (state) {
            conflicts = view.pull(state);
            witness = read.get();
            if (matches.test(witness) && conflicts.isEmpty()) {
                write.run();
                view.push(state);
            }
        }
        handleConflicts(conflicts, view, "compare-and-exchange");
        return witness;
    }

    @Override
    public Object onVolatileArrayRead(StaticObject array, int index) {
        // As onVolatileRead, on the one sync state all the array's elements share. Unlike arrayForRead and
        // arrayForWrite, which are barriers: they only declare an access and hand the storage to the
        // caller, who does the access itself (host code reading or writing the array). A volatile access
        // is a hook the caller hands over whole, so the model does the element access here, inside the
        // sync state's lock, through the view's storage.
        BranchingView view = currentView();
        SyncState<?> state = array.volatileArraySyncState();
        ConflictingLocations conflict;
        Object value;
        synchronized (state) {
            conflict = view.pull(state);
            value = Array.get(volatileArrayForRead(array, index), index);
        }
        handleConflicts(conflict, view, "volatile read");
        return value;
    }

    @Override
    public void onVolatileArrayWrite(StaticObject array, int index, Object value) {
        // as onVolatileWrite, on the one sync state all the array's elements share
        BranchingView view = currentView();
        SyncState<?> state = array.volatileArraySyncState();
        ConflictingLocations conflicts;
        synchronized (state) {
            // the model does the element write itself, through the barrier (see onVolatileArrayRead)
            Array.set(volatileArrayForWrite(array, index), index, value);
            conflicts = view.pull(state);
            if (conflicts.isEmpty()) {
                view.push(state);
            }
        }
        handleConflicts(conflicts, view, "volatile write");
    }

    @Override
    public boolean onArrayCompareAndSet(StaticObject array, int index, StaticObject expected, StaticObject value) {
        // the model does the element access itself (see onVolatileArrayRead)
        return compareAndSet(array.volatileArraySyncState(), () -> Array.get(volatileArrayForRead(array, index), index) == expected,
                        () -> Array.set(volatileArrayForWrite(array, index), index, value));
    }

    @Override
    public boolean onArrayCompareAndSet(StaticObject array, int index, int expected, int value) {
        return compareAndSet(array.volatileArraySyncState(), () -> Array.getInt(volatileArrayForRead(array, index), index) == expected,
                        () -> Array.setInt(volatileArrayForWrite(array, index), index, value));
    }

    @Override
    public boolean onArrayCompareAndSet(StaticObject array, int index, long expected, long value) {
        return compareAndSet(array.volatileArraySyncState(), () -> Array.getLong(volatileArrayForRead(array, index), index) == expected,
                        () -> Array.setLong(volatileArrayForWrite(array, index), index, value));
    }

    @Override
    public StaticObject onArrayCompareAndExchange(StaticObject array, int index, StaticObject expected, StaticObject value) {
        return compareAndExchange(array.volatileArraySyncState(), () -> (StaticObject) Array.get(volatileArrayForRead(array, index), index),
                        witness -> witness == expected, () -> Array.set(volatileArrayForWrite(array, index), index, value));
    }

    @Override
    public int onArrayCompareAndExchange(StaticObject array, int index, int expected, int value) {
        return compareAndExchange(array.volatileArraySyncState(), () -> Array.getInt(volatileArrayForRead(array, index), index),
                        witness -> witness == expected, () -> Array.setInt(volatileArrayForWrite(array, index), index, value));
    }

    @Override
    public long onArrayCompareAndExchange(StaticObject array, int index, long expected, long value) {
        return compareAndExchange(array.volatileArraySyncState(), () -> Array.getLong(volatileArrayForRead(array, index), index),
                        witness -> witness == expected, () -> Array.setLong(volatileArrayForWrite(array, index), index, value));
    }

    @Override
    public byte onArrayCompareAndExchange(StaticObject array, int index, byte expected, byte value) {
        return compareAndExchange(array.volatileArraySyncState(), () -> Array.getByte(volatileArrayForRead(array, index), index),
                        witness -> witness == expected, () -> Array.setByte(volatileArrayForWrite(array, index), index, value));
    }

    @Override
    public short onArrayCompareAndExchange(StaticObject array, int index, short expected, short value) {
        return compareAndExchange(array.volatileArraySyncState(), () -> Array.getShort(volatileArrayForRead(array, index), index),
                        witness -> witness == expected, () -> Array.setShort(volatileArrayForWrite(array, index), index, value));
    }

    @Override
    public StaticObject onArrayGetAndSet(StaticObject array, int index, StaticObject value) {
        return compareAndExchange(array.volatileArraySyncState(), () -> (StaticObject) Array.get(volatileArrayForRead(array, index), index),
                        witness -> true, () -> Array.set(volatileArrayForWrite(array, index), index, value));
    }

    @Override
    public void onClassInitialized(ObjectKlass klass) {
        // Class-init release: pull from the class's init sync state, then push to it.
        BranchingView view = currentView();
        SyncState<?> state = klass.getClassInitSyncState();
        ConflictingLocations conflicts;
        synchronized (state) {
            conflicts = view.pull(state);
            if (conflicts.isEmpty()) {
                view.push(state);
            }
        }
        handleConflicts(conflicts, view, "class init");
    }

    @Override
    public void onClassInitUse(ObjectKlass klass) {
        // Class-init acquire: pull from the class's init sync state, no push.
        BranchingView view = currentView();
        SyncState<?> state = klass.getClassInitSyncState();
        ConflictingLocations conflicts;
        synchronized (state) {
            conflicts = view.pull(state);
        }
        handleConflicts(conflicts, view, "class init");
    }

    private void handleConflicts(ConflictingLocations conflicts, BranchingView view, String at) {
        if (conflicts.isEmpty()) {
            return;
        }
        // Record a pending conflict, raised as a guest exception at the next safe point (top of the
        // bytecode loop, past any just-executed monitorexit). We must NOT throw from inside the monitor
        // op itself: that corrupts the synchronized block's exit machinery. The user code location is
        // taken now, while the thread is still at the action.
        view.setPendingConflict(new ConflictReport(conflicts, at, UserCodeLocation.current(context)));
    }

    @Override
    public boolean hasPendingConflict(MemoryView view) {
        return ((BranchingView) view).hasPendingConflict();
    }

    @Override
    public ConflictReport takePendingConflict(MemoryView view) {
        return ((BranchingView) view).takePendingConflict();
    }
}
