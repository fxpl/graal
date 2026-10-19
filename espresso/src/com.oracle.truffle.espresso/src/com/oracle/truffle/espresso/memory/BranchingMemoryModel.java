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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
 * <li><b>thread end</b> — commit, and record the terminal tip in {@code terminalHeads} for join.</li>
 * </ul>
 *
 * <p><b>Join.</b> There is no dedicated join hook. Guest {@code Thread.join} is
 * {@code synchronized(threadObject)}, so the joiner runs a {@code monitorenter}/{@code monitorexit} on
 * the thread object, and the joinee records its terminal head at {@code onThreadEnd}; the joiner's
 * monitor op then merges it. Two subtleties drive the design:
 * <ul>
 * <li>{@code Object.wait} inside {@code join} releases and re-acquires the monitor <em>without</em> a
 *     bytecode {@code monitorenter}, so the acquire-merge does not fire on wake-up. The joiner's only
 *     reliable post-termination hook is the final {@code monitorexit}, so we merge the joinee's
 *     terminal head at both enter and exit (idempotent).</li>
 * <li>The terminal head must <em>not</em> live in the thread object's {@link LockState}: the joiner's
 *     own {@code monitorenter} republishes its tip to that lock state (and {@code Thread.start} already
 *     took the monitor once), clobbering the joinee's head before the exit could merge it. So terminal
 *     heads live in a separate write-once {@code terminalHeads} map that monitor ops only read.</li>
 * </ul>
 * The {@code signalAll} that wakes the joiner, and the {@code TERMINATED} state it then observes,
 * establish the happens-before from the joinee's record to the joiner's merge.
 *
 * <p>Scope (B1): instance + static fields, monitor lock/unlock, thread start/end, spawn, join. Arrays,
 * volatiles, and the lazy / lazy-suppress conflict policies are later milestones.
 */
public final class BranchingMemoryModel implements MemoryModel {

    private final EspressoContext context;
    private volatile boolean active = false;
    private final AtomicLong threadIds = new AtomicLong();

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

    /**
     * Terminal branch tips of ended threads, keyed by the guest {@code Thread} object, for join.
     * Write-once by the ending thread at {@link #onThreadEnd}; read (merged, never overwritten) by a
     * joiner's monitor op on the thread object. Kept separate from {@link #lockStates} precisely so a
     * joiner's own enter/exit republish cannot clobber the joinee's published head. See class doc.
     *
     * <p><b>Unbounded retention (never removed).</b> Grows with total thread count. Weak keys on the
     * {@code Thread} object do not help: the model versions a constructed thread's own fields, so the
     * commits holding those writes pin every {@code Thread} it makes (an ended thread is not
     * collectable under this model), so the weak key never clears. The retention here is a bounded
     * per-thread constant — a terminal is a shared commit, not a copy.
     */
    private final Map<StaticObject, Commit> terminalHeads = new ConcurrentHashMap<>();

    /** The parent's branch tip captured at spawn. */
    private record ForkPoint(Commit head) {
    }

    /**
     * Records the commit DAG as it is built, for {@code --java.MemoryModelGraph} (see
     * {@link GraphTrace}). Each view emits its commits/merges here as they happen — never
     * reconstructed from the DAG — so the graph is correct even if the DAG is later collapsed.
     * {@code null} when the option is unset (the normal path is untouched but for one null check).
     */
    private final GraphTrace trace;

    public BranchingMemoryModel(EspressoContext context) {
        this.context = context;
        String graphPath = context.getLanguage().getMemoryModelGraphPath();
        this.trace = graphPath.isEmpty() ? null : new GraphTrace(graphPath, false);
        if (trace != null) {
            trace.arm();
        }
        VersioningStats.arm();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public MemoryView newThreadView() {
        return new BranchingView(threadIds.getAndIncrement(), trace);
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
        FieldLocation loc = new FieldLocation(holder, field);
        Object v = currentView().read(loc);
        if (v != MemoryModel.ABSENT) {
            return v;
        }
        // Not staged and no committed version: the shared frozen genesis, else ABSENT (caller reads the
        // slot and captures it here). read() checks committed first, so a committed value shadows genesis.
        Object g = genesis.get(loc);
        return g != null ? g : MemoryModel.ABSENT;
    }

    @Override
    public void writeField(StaticObject holder, Field field, Object value) {
        currentView().stage(new FieldLocation(holder, field), value);
    }

    @Override
    public void captureRead(StaticObject holder, Field field, Object value) {
        // First capture wins (putIfAbsent) so v0 is a single value shared by all threads.
        genesis.putIfAbsent(new FieldLocation(holder, field), value);
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
        }
    }

    @Override
    public void onThreadEnd() {
        // Commit the final staging and record the terminal tip for join. A joiner is synchronized on
        // this thread object, so its monitor op merges terminalHeads[self]. We record it here (before
        // the terminating notify that wakes joiners), in a map monitor ops never overwrite. See class
        // doc for why this cannot live in the thread object's LockState.
        BranchingView view = currentView();
        view.commitStaging();
        if (view.head() != null) {
            terminalHeads.put(context.getCurrentPlatformThread(), view.head());
        }
    }

    @Override
    public void onSpawn(StaticObject childThread) {
        // Fork: commit the parent's staging, then capture its tip for the child, which adopts it at
        // start. Runs on the parent, before the child begins.
        BranchingView view = currentView();
        view.commitStaging();
        pendingForks.put(childThread, new ForkPoint(view.head()));
    }

    @Override
    public void onMonitorEnter(StaticObject lock) {
        // Acquire = commit, merge the lock's published branch, republish this holder's tip. Also merge
        // a terminated thread's head if this monitor is a join target (see mergeTerminal).
        BranchingView view = currentView();
        view.commitStaging();
        LockState state = lockState(lock);
        VersionedLocation conflict = view.merge(state.commit);
        publish(state, view);
        VersionedLocation joinConflict = mergeTerminal(lock, view);
        handleConflict(conflict != null ? conflict : joinConflict, view, MemoryModel.monitorSyncLabel(context, lock, true));
    }

    @Override
    public void onMonitorExit(StaticObject lock) {
        // Release = commit, republish this holder's tip to the lock (gitmem on_unlock; no lock-branch
        // merge — ordinary lock conflicts are caught at the acquire). Also merge a terminated thread's
        // head if this monitor is a join target: this is the point at which join reliably observes the
        // joinee's terminal head (the wait-reacquire inside join fires no monitor hook). See class doc.
        BranchingView view = currentView();
        view.commitStaging();
        publish(lockState(lock), view);
        handleConflict(mergeTerminal(lock, view), view, MemoryModel.monitorSyncLabel(context, lock, false));
    }

    /** Publish the view's current tip to a lock state. */
    private static void publish(LockState state, BranchingView view) {
        state.commit = view.head();
    }

    /**
     * If {@code lock} is a thread object that has ended, merge its terminal tip into this view (join
     * semantics); otherwise a no-op. Merged at both enter and exit, so must be idempotent. Never
     * mutates {@link #terminalHeads}.
     */
    private VersionedLocation mergeTerminal(StaticObject lock, BranchingView view) {
        Commit terminal = terminalHeads.get(lock);
        return terminal == null ? null : view.merge(terminal);
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
            VersionedLocation c = null;
            synchronized (t) {
                v.commitStaging();
                if (t.commit != null) {
                    c = v.merge(t.commit);
                }
            }
            handleConflict(c, v, "class init");
            return StaticObject.NULL;
        }
        BranchingView view = currentView();
        LockState tip = volatileTip(new FieldLocation(holder, field));
        VersionedLocation conflict = null;
        Object value;
        synchronized (tip) {
            view.commitStaging();
            if (tip.commit != null) {
                conflict = view.merge(tip.commit);
            }
            value = field.get(holder, true);   // forceVolatile; value is un-versioned, in the real heap
        }
        handleConflict(conflict, view, "volatile read");
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
            VersionedLocation c = null;
            synchronized (t) {
                v.commitStaging();
                if (t.commit != null) {
                    c = v.merge(t.commit);
                }
                if (c == null) {
                    publish(t, v);
                }
            }
            handleConflict(c, v, "class init");
            return;
        }
        BranchingView view = currentView();
        LockState tip = volatileTip(new FieldLocation(holder, field));
        VersionedLocation conflict = null;
        synchronized (tip) {
            view.commitStaging();
            if (tip.commit != null) {
                conflict = view.merge(tip.commit);
            }
            if (conflict == null) {
                field.set(holder, value, true);   // forceVolatile; un-versioned value in the real heap
                publish(tip, view);
            }
        }
        handleConflict(conflict, view, "volatile write");
    }

    private void handleConflict(VersionedLocation conflict, BranchingView view, String at) {
        if (conflict == null) {
            return;
        }
        // Record a pending conflict, raised as a guest exception at the next safe point (top of the
        // bytecode loop, past any just-executed monitorexit), or by GuestRunnable if this was the
        // thread's final sync. We must NOT throw from inside the monitor op itself: that corrupts the
        // synchronized block's exit machinery. The conflicted merge commit already advanced head, so
        // the branch stays consistent if the conflict is caught and execution continues.
        view.setPendingConflict("Sync-as-Share conflict: incompatible concurrent update to " + describe(conflict)
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
