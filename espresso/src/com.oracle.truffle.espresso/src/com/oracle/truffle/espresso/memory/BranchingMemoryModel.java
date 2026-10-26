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
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * The Branching version-controlled memory model (paper: Sync-as-Share, the branching model), ported
 * from the {@code gitmem} prototype's {@code branching::eager} version store. This is the <b>eager</b>
 * policy — the paper's model — so a conflict is detected <em>at the merge</em> (the synchronisation
 * point), and ordinary reads never conflict.
 *
 * <p>Unlike {@link LinearMemoryModel}, there is no single global history {@code g}: communication is
 * pairwise, through the commit DAG. A synchronisation action commits the acting thread's staged writes
 * into a {@link Commit} on its own branch, then merges another branch in:
 * <ul>
 * <li><b>spawn</b> — the parent commits and the child adopts the parent's branch tip (fork);</li>
 * <li><b>monitor enter</b> — commit, then merge the lock's published branch ({@link LockState}), then
 *     republish the holder's tip to the lock;</li>
 * <li><b>monitor exit</b> — commit and republish the holder's tip to the lock;</li>
 * <li><b>thread end</b> — commit, and record the final commit in ... for
 *     join.</li>
 * </ul>
 *
 * <p><b>Join (stop-gap).</b> There is no join hook: guest {@code Thread.join} locks the {@code Thread}
 * object and waits, and Espresso wakes joiners from host code the model never sees. So a finished
 * thread's final commit is kept in {@code finishedThreadCommits}, and monitor enter/exit on a lock that
 * is actually a finished thread merges it explicitly. To be replaced by modelling join properly (see
 * the worklist's "replace {@code terminalHeads}" row).
 *
 * <p>Scope (B1): instance + static fields, monitor lock/unlock, thread start/end, spawn, join. Arrays,
 * volatiles, and the lazy / lazy-suppress conflict policies are later milestones.
 */
public final class BranchingMemoryModel implements MemoryModel {

    private final EspressoContext context;
    private volatile boolean active = false;

    // Sync-as-Share (stage-on-access): the shared version-0 cache. The first thread to read a versioned
    // field with no staged/committed value freezes the physical (genesis) value here; every thread then
    // reads that one frozen value instead of the live slot, so v0 is a single fixed version (the paper's
    // root commit) rather than a per-thread capture. Populated once per location via putIfAbsent.
    private final Map<VersionedLocation, Object> genesis = new ConcurrentHashMap<>();

    /** Per-monitor (and per-thread-object, for join) published branch tip. Keyed by object identity. */
    private final Map<StaticObject, LockState> lockStates = new ConcurrentHashMap<>();

    /**
     * Per-volatile-field published branch tip — the paper's map {@code V} of the latest commit of each
     * volatile variable (§5.3 / Table 2). Keyed by {@link FieldLocation} (so a volatile instance field
     * is a distinct sync object per receiver). Separate from {@link #lockStates}: a volatile field is
     * its own synchronisation object, not a monitor.
     */
    private final Map<FieldLocation, LockState> volatileTips = new ConcurrentHashMap<>();

    /** Fork points recorded at spawn, keyed by the child's guest {@code Thread}; consumed at its start. */
    private final Map<StaticObject, ForkPoint> pendingForks = new ConcurrentHashMap<>();

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
    private static final Cleaner cleaner = Cleaner.create();

    private void registerForCleaning(StaticObject object) {
        if (!object.isCleanerRegistered()) {
            object.markCleanerRegistered();
            cleaner.register(object, new MarkObjectForPurging(object.getObjectNumber(), deadObjectCount));
            VersioningStats.onCleanerRegistered();
        }
    }

    /** The parent's branch tip captured at spawn. */
    private record ForkPoint(Commit head) {
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

    @Override
    public MemoryView newThreadView() {
        return new BranchingView(deadObjectCount);
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

    @Override
    public int pendingWriteCount() {
        MemoryView v = context.getLanguage().getThreadLocalState().peekMemoryView();
        return v == null ? 0 : ((BranchingView) v).stagingSize();
    }

    private LockState lockState(StaticObject key) {
        return lockStates.computeIfAbsent(key, k -> new LockState());
    }

    private LockState volatileTip(FieldLocation key) {
        return volatileTips.computeIfAbsent(key, k -> new LockState());
    }

    // Sync-as-Share: per-class init cell, keyed by the class mirror (the init-state's guest handle).
    // The class-init edge (JLS §12.4.2) is a volatile on this cell: released at <clinit> completion,
    // acquired (merge only) on first use.
    private final Map<StaticObject, LockState> classInitTips = new ConcurrentHashMap<>();

    private LockState classInitTip(StaticObject mirror) {
        return classInitTips.computeIfAbsent(mirror, k -> new LockState());
    }

    // --- Ordinary field / array access: stage and read on the calling thread's view. ---

    @Override
    public Object readField(StaticObject holder, Field field) {
        FieldLocation loc = new FieldLocation(holder.getObjectNumber(), field);

        if (holder.hasBeenVersioned()) {
            Object v = currentView().read(loc);
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
        currentView().stage(new FieldLocation(holder.getObjectNumber(), field), value);
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
     * this thread's staged-or-committed elements ({@link BranchingView#read}). Cached on the view and
     * reused until the next commit clears it. See {@link ArrayVersion}.
     */
    private ArrayVersion arrayVersionFor(StaticObject array) {
        BranchingView view = currentView();
        ArrayVersion av = view.arrayVersion(array);
        if (av == null) {
            Object physical = context.getLanguage().getArrayProperty().getObject(array);
            int n = java.lang.reflect.Array.getLength(physical);
            Object host = java.lang.reflect.Array.newInstance(physical.getClass().getComponentType(), n);
            System.arraycopy(physical, 0, host, 0, n);
            for (int i = 0; i < n; i++) {
                Object v = view.read(new ArrayLocation(array, i));
                if (v != MemoryModel.ABSENT) {
                    java.lang.reflect.Array.set(host, i, v);
                }
            }
            av = new ArrayVersion(host);
            view.putArrayVersion(array, av);
        }
        return av;
    }

    // --- Synchronisation actions (Table 1). May report a conflict at the merge. ---

    @Override
    public void onThreadStart() {
        // Adopt the parent's branch captured at spawn, if any. The main thread has no fork point.
        StaticObject self = context.getCurrentPlatformThread();
        ForkPoint fork = pendingForks.remove(self);
        if (fork != null) {
            currentView().adopt(fork.head());
            if (fork.head() != null) {
                fork.head().decRef();
            }
        }
    }

    @Override
    public void onThreadEnd() {
        // Commit the final staging and record the final commit for join,
        // before the terminating notify that wakes joiners.
        BranchingView view = currentView();
        view.commitStaging();

        // Set the hidden thread slot to store the final commit, this lets a joining thread
        // pick up the commit even once the thread has been deallocated
        StaticObject thread = context.getCurrentPlatformThread();
        context.getMeta().java_lang_Thread_0finalCommit.setHiddenObject(thread, view.head());

        // register that when the thread object is cleaned away, we also drop
        // the reference count of the terminalHead.
        // The Commit itself will be GC'd but we need to not lose transitive refCounts
        Commit terminalHead = view.head();
        cleaner.register(thread, () -> { terminalHead.decRef(); });
    }

    @Override
    public void onJoin(StaticObject joined) {
        // Join (paper Join rule): this thread detected that `joined` terminated, so pull the final
        // commit the joined thread stored on its Thread object. Absent if it never committed anything.
        Object finalCommit = context.getMeta().java_lang_Thread_0finalCommit.getHiddenObject(joined);
        if (!(finalCommit instanceof Commit commit)) {
            return;
        }
        BranchingView view = currentView();
        view.commitStaging();
        handleConflicts(view.merge(commit), view, "join");

        // Don't worry about compressing now, the object representing the thread is still
        // going to exist until it is GC'd so we may as well wait until then
    }

    @Override
    public void onSpawn(StaticObject childThread) {
        // Fork: commit the parent's staging, then capture its tip for the child, which adopts it at
        // start. Runs on the parent, before the child begins.
        BranchingView view = currentView();
        view.commitStaging();
        pendingForks.put(childThread, new ForkPoint(view.head()));

        // set an extra reference to the head for the child which is waiting to start
        if (view.head() != null) {
            view.head().incRef();
        }
    }

    @Override
    public void onMonitorEnter(StaticObject lock) {
        // Acquire = commit, merge the lock's published branch, republish this holder's tip.
        BranchingView view = currentView();
        view.commitStaging();
        LockState state = lockState(lock);
        Set<VersionedLocation> conflicts = new HashSet<>(view.merge(state.commit));
        handleConflicts(conflicts, view, MemoryModel.monitorSyncLabel(context, lock, true));
    }

    @Override
    public void onMonitorExit(StaticObject lock) {
        // Release = commit, republish this holder's tip to the lock (no lock-branch merge: ordinary lock
        // conflicts are caught at the acquire).
        BranchingView view = currentView();

        LockState lockState = lockState(lock);
        if (lockState.commit != null) {
            lockState.commit.decRef();
            lockState.commit = null;
        }
        view.commitStaging();
        publish(lockState, view);

        // the thread and lock now have the same head, so try to
        // compress the merge (and history) that came before
        view.compress();
    }

    /** Publish the view's current tip to a lock state. */
    private static void publish(LockState state, BranchingView view) {
        Commit newHeadForSyncObject = view.head();
        if (newHeadForSyncObject != null) {
            newHeadForSyncObject.incRef();
        }
        if (state.commit != null) {
            state.commit.decRef();
        }
        state.commit = newHeadForSyncObject;
    }

    @Override
    public Object onVolatileRead(StaticObject holder, Field field) {
        // Volatile read (paper VolatileRead): acquire by merging the field's published commit into this
        // view, then read the un-versioned value straight from the physical slot. commitStaging first,
        // so the merge sees empty staging and its conflict check covers the reader's ordinary writes;
        // no publish — a read adds no history. Atomic per field (synchronised on the tip) so value and
        // commit are read together, consistent against a concurrent volatile write to the same field.
        if (field == null) {   // class-init acquire: merge the class's init tip only — no republish, no heap value
            BranchingView v = currentView();
            LockState t = classInitTip(holder);
            Set<VersionedLocation> conflicts = Set.of();
            synchronized (t) {
                v.commitStaging();
                if (t.commit != null) {
                    conflicts = v.merge(t.commit);
                }
            }
            handleConflicts(conflicts, v, "class init");
            return StaticObject.NULL;
        }
        BranchingView view = currentView();
        LockState tip = volatileTip(new FieldLocation(holder.getObjectNumber(), field));
        registerForCleaning(holder);
        Set<VersionedLocation> conflict = Set.of();
        Object value;
        synchronized (tip) {
            view.commitStaging();
            if (tip.commit != null) {
                conflict = view.merge(tip.commit);
            }
            value = field.get(holder, true);   // forceVolatile; value is un-versioned, in the real heap
        }
        handleConflicts(conflict, view, "volatile read");
        return value;
    }

    @Override
    public void onVolatileWrite(StaticObject holder, Field field, Object value) {
        // Volatile write (paper VolatileWrite): acquire (commit ordinary staging, merge the field's
        // prior commit), then release — set the un-versioned value in the physical slot and publish this
        // thread's head as the field's new commit. The value never enters a commit, so concurrent
        // volatile writes to the same field are not a data race (last writer wins on the slot); only the
        // ordinary writes carried on the commit are conflict-checked, at the merge. Atomic per field.
        if (field == null) {   // class-init release: merge then publish this thread's tip — no heap value
            BranchingView v = currentView();
            LockState t = classInitTip(holder);
            Set<VersionedLocation> conflicts = Set.of();
            synchronized (t) {
                v.commitStaging();
                if (t.commit != null) {
                    conflicts = v.merge(t.commit);
                }
                if (conflicts.isEmpty()) {
                    publish(t, v);
                }
            }
            handleConflicts(conflicts, v, "class init");
            return;
        }
        BranchingView view = currentView();
        LockState tip = volatileTip(new FieldLocation(holder.getObjectNumber(), field));
        registerForCleaning(holder);
        Set<VersionedLocation> conflicts = Set.of();
        synchronized (tip) {
            view.commitStaging();
            if (tip.commit != null) {
                conflicts = view.merge(tip.commit);
            }
            if (conflicts.isEmpty()) {
                field.set(holder, value, true);   // forceVolatile; un-versioned value in the real heap
                publish(tip, view);
            }
        }
        handleConflicts(conflicts, view, "volatile write");
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
                        + ", caught at " + at + " in " + context.getVM().topGuestSite());
    }

    private static String describe(VersionedLocation loc) {
        if (loc instanceof FieldLocation f) {
            return f.field().getDeclaringKlass().getName() + "." + f.field().getName();
        } else if (loc instanceof ArrayLocation a) {
            return a.array().getKlass().getName() + "[" + a.index() + "]";
        }
        return String.valueOf(loc);
    }
}
