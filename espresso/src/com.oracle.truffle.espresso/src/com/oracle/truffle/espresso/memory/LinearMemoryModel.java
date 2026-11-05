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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

public final class LinearMemoryModel implements MemoryModel {

    private final SyncState global = new SyncState(SyncState.Kind.GLOBAL);

    private final EspressoContext context;
    private volatile boolean active = false;

    private final AtomicLong deadObjectCount;
    private final Cleaner cleaner = Cleaner.create();

    @Override
    public Cleaner getCleaner() {
        return cleaner;
    }

    public LinearMemoryModel(EspressoContext context) {
        this.context = context;
        this.deadObjectCount = new AtomicLong(0);
        VersioningStats.arm("linear");
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public MemoryView newThreadView() {
        return new LinearView(this::bindThreadSyncState, global, deadObjectCount);
    }

    private SyncState newThreadSyncState(StaticObject thread) {
        SyncState state = new SyncState(SyncState.Kind.THREAD);
        context.getMeta().java_lang_Thread_0syncState.setHiddenObject(thread, state);
        cleaner.register(thread, () -> state.set(null));
        return state;
    }

    private SyncState bindThreadSyncState() {
        StaticObject thread = context.getCurrentPlatformThread();
        assert thread != null : "binding a view before its thread is registered";

        Object existing = context.getMeta().java_lang_Thread_0syncState.getHiddenObject(thread);
        if (existing instanceof SyncState s) {
            return s;
        }
        return newThreadSyncState(thread);
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

    // This is a record so that we don't accidentally capture the StaticObject
    // if we did that, we could end up keeping a reference to the StaticObject in the cleaner
    // register set and then the object could never be GC'd
    record MarkObjectForPurging(ObjectNumber objectNumber, AtomicLong deadObjectCount) implements Runnable {
        @Override
        public void run() {
            objectNumber.setDead();
            deadObjectCount.incrementAndGet();
            VersioningStats.onMarkedForPurging();
        }
    }

    private void registerForCleaning(StaticObject object) {
        if (!object.isCleanerRegistered()) {
            object.markCleanerRegistered();
            cleaner.register(object, new MarkObjectForPurging(object.getObjectNumber(), deadObjectCount));
            VersioningStats.onCleanerRegistered();
        }
    }

    private LinearView currentView() {
        return (LinearView) context.getLanguage().getThreadLocalState().getMemoryView();
    }

    @Override
    public int pendingWriteCount() {
        MemoryView v = context.getLanguage().getThreadLocalState().peekMemoryView();
        return v == null ? 0 : ((LinearView) v).stagingSize();
    }

    private void handleConflicts(ConflictingLocations conflicts, LinearView view, String at) {
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
    public Object readField(StaticObject holder, Field field) {
        if (holder.hasVersionedWrite()) {
            return currentView().readField(holder, field);
        }
        // no thread has written the object, so every field is at version 0, its raw slot
        return field.getRawValue(holder);
    }

    @Override
    public void writeField(StaticObject holder, Field field, Object value) {
        currentView().stageField(holder, field, value);
        holder.markHasVersionedWrite();
        registerForCleaning(holder);
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

    @Override
    public void onThreadBegin() {
        LinearView view = currentView();
        view.activate();
        ConflictingLocations conflicts = view.pull();
        assert conflicts.isEmpty() : "starting thread cannot conflict";
    }

    @Override
    public void onThreadEnd() {
        ConflictingLocations conflicts = currentView().push();
        if (!conflicts.isEmpty()) {
            // A dying thread cannot act on a conflict, so it is left on the thread for every joiner to
            // raise; the conflicting writes never reach the global history. Set before the thread
            // terminates, so before any joiner is woken.
            ConflictReport report = new ConflictReport(conflicts, "thread end", UserCodeLocation.current(context));
            context.getMeta().java_lang_Thread_0threadEndConflict.setHiddenObject(context.getCurrentPlatformThread(), report, true);
        }
    }

    @Override
    public void onJoin(StaticObject joined) {
        // The joined thread pushed everything to the global history at its end, so a join is a pull
        LinearView view = currentView();
        handleConflicts(view.pull(), view, "join");

        // unless its push conflicted, then the joiner takes responsibility for the conflict
        Object threadEndConflict = context.getMeta().java_lang_Thread_0threadEndConflict.getHiddenObject(joined, true);
        if (threadEndConflict instanceof ConflictReport report) {
            view.setPendingConflict(report);
        }
    }

    @Override
    public void onSpawn(StaticObject childThread) {
        // first mark what the child can now reach, so the push carries it
        Versioning.onSpawn(childThread);
        LinearView view = currentView();
        handleConflicts(view.push(), view, "spawn");
    }

    @Override
    public void onMonitorEnter(StaticObject lock) {
        LinearView view = currentView();
        handleConflicts(view.pull(), view, MemoryModel.monitorSyncLabel(context, lock, true));
    }

    @Override
    public void onMonitorExit(StaticObject lock) {
        LinearView view = currentView();
        handleConflicts(view.push(), view, MemoryModel.monitorSyncLabel(context, lock, false));
    }

    @Override
    public Object onVolatileRead(StaticObject holder, Field field) {
        LinearView view = currentView();
        SyncState state = holder.volatileSyncState(field);
        ConflictingLocations conflict;
        Object value = null;
        // pull, then read the value through the view, like any versioned value
        synchronized (state) {
            conflict = view.pull();
            value = readField(holder, field);
        }
        handleConflicts(conflict, view, "volatile read");
        return value;
    }

    @Override
    public void onVolatileWrite(StaticObject holder, Field field, Object value) {
        LinearView view = currentView();
        SyncState state = holder.volatileSyncState(field);
        ConflictingLocations conflict;
        // stage the value like any write, then push it; the push pulls first, so a volatile write
        // unordered with ours conflicts (the JLS counts it as a data race)
        synchronized (state) {
            writeField(holder, field, value);
            conflict = view.push();
        }
        handleConflicts(conflict, view, "volatile write");
    }

    @Override
    public boolean onCompareAndSet(StaticObject holder, Field field, int expected, int value) {
        return compareAndSet(holder.volatileSyncState(field), () -> (int) readField(holder, field) == expected, () -> writeField(holder, field, value));
    }

    @Override
    public boolean onCompareAndSet(StaticObject holder, Field field, long expected, long value) {
        return compareAndSet(holder.volatileSyncState(field), () -> (long) readField(holder, field) == expected, () -> writeField(holder, field, value));
    }

    @Override
    public boolean onCompareAndSet(StaticObject holder, Field field, StaticObject expected, StaticObject value) {
        return compareAndSet(holder.volatileSyncState(field), () -> readField(holder, field) == expected, () -> writeField(holder, field, value));
    }

    // pull, test, then write and push, all atomic on the location's sync state; the read comes first, so
    // the write is informed by the last one. matches reads the location after the pull, typed by the caller.
    private boolean compareAndSet(SyncState state, BooleanSupplier matches, Runnable write) {
        LinearView view = currentView();
        ConflictingLocations conflict;
        boolean swapped;
        synchronized (state) {
            conflict = view.pull();
            swapped = matches.getAsBoolean();
            if (swapped && conflict.isEmpty()) {
                write.run();
                conflict = view.push();
            }
        }
        handleConflicts(conflict, view, "compare-and-set");
        return swapped;
    }

    @Override
    public Object onVolatileArrayRead(StaticObject array, int index) {
        // As onVolatileRead, on the one sync state all the array's elements share. Unlike arrayForRead and
        // arrayForWrite, which are barriers: they only declare an access and hand the storage to the
        // caller, who does the access itself (host code reading or writing the array). A volatile access
        // is a hook the caller hands over whole, so the model does the element access here, inside the
        // sync state's lock, through the view's storage.
        LinearView view = currentView();
        SyncState state = array.volatileArraySyncState();
        ConflictingLocations conflict;
        Object value;
        synchronized (state) {
            conflict = view.pull();
            value = Array.get(arrayForRead(array, index), index);
        }
        handleConflicts(conflict, view, "volatile read");
        return value;
    }

    @Override
    public void onVolatileArrayWrite(StaticObject array, int index, Object value) {
        // as onVolatileWrite, on the one sync state all the array's elements share
        LinearView view = currentView();
        SyncState state = array.volatileArraySyncState();
        ConflictingLocations conflict;
        synchronized (state) {
            // the model does the element write itself, through the barrier (see onVolatileArrayRead)
            Array.set(arrayForWrite(array, index), index, value);
            conflict = view.push();
        }
        handleConflicts(conflict, view, "volatile write");
    }

    @Override
    public boolean onArrayCompareAndSet(StaticObject array, int index, StaticObject expected, StaticObject value) {
        // the model does the element access itself (see onVolatileArrayRead)
        return compareAndSet(array.volatileArraySyncState(), () -> Array.get(arrayForRead(array, index), index) == expected,
                        () -> Array.set(arrayForWrite(array, index), index, value));
    }

    @Override
    public void onClassInitialized(ObjectKlass klass) {
        // Class-init release: a push to the global history, which pulls first and is atomic under the
        // global lock, so it needs no lock of the class's own
        LinearView view = currentView();
        handleConflicts(view.push(), view, "class init");
    }

    @Override
    public void onClassInitUse(ObjectKlass klass) {
        // Class-init acquire: a pull from the global history, which carries every class's init
        LinearView view = currentView();
        handleConflicts(view.pull(), view, "class init");
    }

    @Override
    public boolean hasPendingConflict(MemoryView view) {
        return ((LinearView) view).hasPendingConflict();
    }

    @Override
    public ConflictReport takePendingConflict(MemoryView view) {
        return ((LinearView) view).takePendingConflict();
    }
}
