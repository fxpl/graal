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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

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
 * through a DAG of reference-counted {@link Commit}s. Each thread works on a {@link BranchingView}
 * whose head is held in the thread's {@link SyncState}. Every synchronisation action is a
 * <b>pull</b> (commit staged writes, then merge another sync state's commit in) and/or a <b>push</b>
 * (commit, then point another sync state at our head):
 * <ul>
 * <li><b>spawn</b>: push to the child thread's sync state;</li>
 * <li><b>join</b>: pull from the joined thread's sync state;</li>
 * <li><b>monitor enter / exit</b>: pull from / push to the lock's sync state;</li>
 * <li><b>volatile read / write</b>: pull / pull then push, on the field's sync state;</li>
 * <li><b>class init</b>: pull then push on initialisation, pull on first use;</li>
 * <li><b>thread end</b>: commit.</li>
 * </ul>
 *
 * <p>Sync states live on their owners: a thread's in a hidden field on the {@code Thread}, lock and
 * volatile states in the object's {@link ObjectSyncState}, class-init state on the {@link ObjectKlass}.
 * A {@link java.lang.ref.Cleaner} releases an owner's commits when the owner dies.
 */
public final class BranchingMemoryModel implements MemoryModel {

    private final EspressoContext context;
    private volatile boolean active = false;

    // Sync-as-Share (stage-on-access): the shared version-0 cache. The first thread to read a versioned
    // field with no staged/committed value freezes the physical (genesis) value here; every thread then
    // reads that one frozen value instead of the live slot, so v0 is a single fixed version (the paper's
    // root commit) rather than a per-thread capture. Populated once per location via putIfAbsent.
    private final Map<VersionedLocation, Object> genesis = new ConcurrentHashMap<>();

    private final AtomicLong deadObjectCount;

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

    /* A cleaner to purge out object numbers that represent objects GC'd */
    private final Cleaner cleaner = Cleaner.create();

    @Override
    public Cleaner getCleaner() {
        return cleaner;
    }

    private void registerForCleaning(StaticObject object) {
        if (!object.isCleanerRegistered()) {
            object.markCleanerRegistered();
            cleaner.register(object, new MarkObjectForPurging(object.getObjectNumber(), deadObjectCount));
            VersioningStats.onCleanerRegistered();
        }
    }

    public BranchingMemoryModel(EspressoContext context) {
        this.context = context;
        this.deadObjectCount = new AtomicLong(0);
        VersioningStats.arm();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    public MemoryView newThreadView() {
        return new BranchingView(this::bindThreadSyncState, deadObjectCount);
    }

    private SyncState newThreadSyncState(StaticObject thread) {
        SyncState state = new SyncState();

        // store the state on the thread object itself
        context.getMeta().java_lang_Thread_0syncState.setHiddenObject(thread, state);

        // register that when the thread object is cleaned away, we also drop
        // the reference count of the terminalHead.
        // The Commit itself will be GC'd but we need to not lose transitive refCounts
        cleaner.register(thread, () -> { if (state.commit != null) state.commit.decRef(); });
        return state;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void activate() {
        active = true;
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
    public int pendingWriteCount() {
        MemoryView v = context.getLanguage().getThreadLocalState().peekMemoryView();
        return v == null ? 0 : ((BranchingView) v).stagingSize();
    }

    // --- Ordinary field / array access: stage and read on the calling thread's view. ---

    @Override
    public Object readField(StaticObject holder, Field field) {
        FieldLocation loc = new FieldLocation(holder.getObjectNumber(), field);

        if (holder.hasBeenVersioned()) {
            Object v = currentView().readField(holder, field);
            if (v != MemoryModel.ABSENT) {
                return v;
            }
        }

        // Not staged and no committed version: the shared frozen genesis, else ABSENT (caller reads the
        // slot and captures it here). read() checks committed first, so a committed value shadows genesis.
        Object g = genesis.get(loc);
        return g != null ? g : MemoryModel.ABSENT;
    }

    @Override
    public void writeField(StaticObject holder, Field field, Object value) {
        currentView().stageField(holder, field, value);
        holder.markHasBeenVersioned();
        registerForCleaning(holder);
    }

    @Override
    public void captureRead(StaticObject holder, Field field, Object value) {
        // First capture wins (putIfAbsent) so v0 is a single value shared by all threads.
        genesis.putIfAbsent(new FieldLocation(holder.getObjectNumber(), field), value);
        registerForCleaning(holder);
    }

    @Override
    public Object readArray(StaticObject array, int index) {
        return java.lang.reflect.Array.get(arrayVersionFor(array).host, index);
    }

    @Override
    public void writeArray(StaticObject array, int index, Object value) {
        ArrayVersion av = arrayVersionFor(array);
        java.lang.reflect.Array.set(av.host, index, value);
        av.touched.set(index);
    }

    @Override
    public Object arrayView(StaticObject array) {
        return arrayVersionFor(array).host;
    }

    @Override
    public void markArrayRange(StaticObject array, int destPos, int length) {
        arrayVersionFor(array).touched.set(destPos, destPos + length);
    }

    /**
     * The acting thread's working copy of {@code array}, materialising it on first access: a real host
     * array of the array's component type, seeded from the physical (version-0) array and overlaid with
     * this thread's staged-or-committed elements ({@link BranchingView#readArray}). Cached on the view and
     * reused until the next commit clears it. See {@link ArrayVersion}.
     */
    private ArrayVersion arrayVersionFor(StaticObject array) {
        BranchingView view = currentView();
        ObjectNumber number = array.getObjectNumber();
        ArrayVersion av = view.arrayVersion(number);
        if (av == null) {
            // the array may now reach a commit, so it must be reported when the GC collects it
            registerForCleaning(array);
            Object physical = context.getLanguage().getArrayProperty().getObject(array);
            int n = java.lang.reflect.Array.getLength(physical);
            Object host = java.lang.reflect.Array.newInstance(physical.getClass().getComponentType(), n);
            System.arraycopy(physical, 0, host, 0, n);
            for (int i = 0; i < n; i++) {
                Object v = view.readArray(number, i);
                if (v != MemoryModel.ABSENT) {
                    java.lang.reflect.Array.set(host, i, v);
                }
            }
            av = new ArrayVersion(host);
            view.putArrayVersion(number, av);
        }
        return av;
    }

    // --- Synchronisation actions (Table 1). May report a conflict at the merge. ---

    @Override
    public void onThreadStart() {
        // start has nothing to do, the parent put the commit in the thread sync state slot
    }

    @Override
    public void onThreadEnd() {
        // Commit the final staging and record the final commit for join,
        // before the terminating notify that wakes joiners.
        // We have nowhere to push, but the node needs to become visible to other
        // threads via the thread sync state.
        // The SyncState will be tidied away and the commit decrefed once the
        // thread object is GC'd
        currentView().commit();
    }

    @Override
    public void onJoin(StaticObject joined) {
        // This thread detected that `joined` terminated, so pull the final
        // commit the joined thread stored on its Thread object. Absent if it never committed anything.
        Object finalSyncState = context.getMeta().java_lang_Thread_0syncState.getHiddenObject(joined);
        if (!(finalSyncState instanceof SyncState state)) {
            return;
        }
        BranchingView view = currentView();
        handleConflicts(view.pull(state), view, "join");
    }

    @Override
    public void onSpawn(StaticObject childThread) {
        // Fork: the parent pushes to the child, which pulls it at start. Runs on the parent, before the
        // child begins.
        BranchingView view = currentView();
        SyncState childState = newThreadSyncState(childThread);
        view.push(childState);
    }

    @Override
    public void onMonitorEnter(StaticObject lock) {
        // pull from the lock
        SyncState state = lock.lockSyncState();
        BranchingView view = currentView();
        handleConflicts(view.pull(state), view, MemoryModel.monitorSyncLabel(context, lock, true));
    }

    @Override
    public void onMonitorExit(StaticObject lock) {
        // push to the lock
        BranchingView view = currentView();
        SyncState state = lock.lockSyncState();
        view.push(state);
    }

    @Override
    public Object onVolatileRead(StaticObject holder, Field field) {
        // Volatile read (paper VolatileRead): pull from the field's sync state, then read the
        // un-versioned value straight from the physical slot. No push — a read adds no history. Atomic
        // per field (synchronised on the sync state) so value and commit are read together, consistent
        // against a concurrent volatile write to the same field.
        BranchingView view = currentView();
        SyncState state = holder.volatileSyncState(field);
        Set<VersionedLocation> conflict;
        Object value;
        synchronized (state) {
            conflict = view.pull(state);
            value = field.get(holder, true);   // forceVolatile; value is un-versioned, in the real heap
        }
        handleConflicts(conflict, view, "volatile read");
        return value;
    }

    @Override
    public void onVolatileWrite(StaticObject holder, Field field, Object value) {
        // Volatile write (paper VolatileWrite): pull from the field's sync state, set the un-versioned
        // value in the physical slot, then push to the sync state. The value never enters a commit, so
        // concurrent volatile writes to the same field are not a data race (last writer wins on the
        // slot); only the ordinary writes carried on the commit are conflict-checked, at the merge.
        // Atomic per field.
        BranchingView view = currentView();
        SyncState state = holder.volatileSyncState(field);
        Set<VersionedLocation> conflicts;
        synchronized (state) {
            conflicts = view.pull(state);
            if (conflicts.isEmpty()) {
                field.set(holder, value, true);   // forceVolatile; un-versioned value in the real heap
                view.push(state);
            }
        }
        handleConflicts(conflicts, view, "volatile write");
    }

    @Override
    public void onClassInitialized(ObjectKlass klass) {
        // Class-init release: pull from the class's init sync state, then push to it.
        BranchingView view = currentView();
        SyncState state = klass.getClassInitSyncState();
        Set<VersionedLocation> conflicts;
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
        SyncState state = klass.getClassInitSyncState();
        Set<VersionedLocation> conflicts;
        synchronized (state) {
            conflicts = view.pull(state);
        }
        handleConflicts(conflicts, view, "class init");
    }

    private void handleConflicts(Set<VersionedLocation> conflicts, BranchingView view, String at) {
        if (conflicts.isEmpty()) {
            return;
        }
        // Record a pending conflict, raised as a guest exception at the next safe point (top of the
        // bytecode loop, past any just-executed monitorexit), or by GuestRunnable if this was the
        // thread's final sync. We must NOT throw from inside the monitor op itself: that corrupts the
        // synchronized block's exit machinery.

        String where = conflicts.stream().map(BranchingMemoryModel::describe).sorted()
                            .collect(Collectors.joining(", "));
        view.setPendingConflict("Sync-as-Share conflict: incompatible concurrent update to " + where
                        + ", caught at " + at + " in " + UserCodeLocation.current(context));
    }

    private static String describe(VersionedLocation loc) {
        if (loc instanceof FieldLocation f) {
            return f.field().getDeclaringKlass().getName() + "." + f.field().getName();
        } else if (loc instanceof ArrayLocation a) {
            return "array element [" + a.index() + "]";
        }
        return String.valueOf(loc);
    }
}
