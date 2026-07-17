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

    /** Per-monitor (and per-thread-object, for join) published branch tip. Keyed by object identity. */
    private final Map<StaticObject, LockState> lockStates = new ConcurrentHashMap<>();

    /** Fork points recorded at spawn, keyed by the child's guest {@code Thread}; consumed at its start. */
    private final Map<StaticObject, ForkPoint> pendingForks = new ConcurrentHashMap<>();

    /**
     * Terminal branch tips of ended threads, keyed by the guest {@code Thread} object, for join.
     * Write-once by the ending thread at {@link #onThreadEnd}; read (merged, never overwritten) by a
     * joiner's monitor op on the thread object. Kept separate from {@link #lockStates} precisely so a
     * joiner's own enter/exit republish cannot clobber the joinee's published head. See class doc.
     */
    private final Map<StaticObject, Tip> terminalHeads = new ConcurrentHashMap<>();

    /** A published branch tip: a commit and the publisher's frontier clock at publication. */
    private record Tip(Commit head, VectorClock clock) {
    }

    /** A snapshot of the parent's branch tip, committed cache, and frontier clock, captured at spawn. */
    private record ForkPoint(Commit head, Map<VersionedLocation, Commit> lastWriter, VectorClock clock) {
    }

    public BranchingMemoryModel(EspressoContext context) {
        this.context = context;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public MemoryView newThreadView() {
        return new BranchingView(threadIds.getAndIncrement());
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

    private LockState lockState(StaticObject key) {
        return lockStates.computeIfAbsent(key, k -> new LockState());
    }

    // --- Ordinary field / array access: stage and read on the calling thread's view. ---

    @Override
    public Object readField(StaticObject holder, Field field) {
        return currentView().read(new FieldLocation(holder, field));
    }

    @Override
    public void writeField(StaticObject holder, Field field, Object value) {
        currentView().stage(new FieldLocation(holder, field), value);
    }

    @Override
    public Object readArray(StaticObject array, int index) {
        return currentView().read(new ArrayLocation(array, index));
    }

    @Override
    public void writeArray(StaticObject array, int index, Object value) {
        currentView().stage(new ArrayLocation(array, index), value);
    }

    // --- Synchronisation actions (Table 1). May report a conflict at the merge. ---

    @Override
    public void onThreadStart() {
        // Adopt the parent's branch captured at spawn, if any. The main thread has no fork point.
        StaticObject self = context.getCurrentPlatformThread();
        ForkPoint fork = pendingForks.remove(self);
        if (fork != null) {
            currentView().adopt(fork.head(), fork.lastWriter(), fork.clock());
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
            terminalHeads.put(context.getCurrentPlatformThread(), new Tip(view.head(), new VectorClock(view.clock())));
        }
    }

    @Override
    public void onSpawn(StaticObject childThread) {
        // Fork: commit the parent's staging, then snapshot its tip + committed cache + frontier clock
        // for the child, which adopts it at start. Runs on the parent, before the child begins.
        BranchingView view = currentView();
        view.commitStaging();
        pendingForks.put(childThread, new ForkPoint(view.head(), view.snapshotLastWriter(), new VectorClock(view.clock())));
    }

    @Override
    public void onMonitorEnter(StaticObject lock) {
        // Acquire = commit, merge the lock's published branch, republish this holder's tip. Also merge
        // a terminated thread's head if this monitor is a join target (see mergeTerminal).
        BranchingView view = currentView();
        view.commitStaging();
        LockState state = lockState(lock);
        VersionedLocation conflict = view.merge(state.commit, state.clock);
        publish(state, view);
        VersionedLocation joinConflict = mergeTerminal(lock, view);
        handleConflict(conflict != null ? conflict : joinConflict, view);
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
        handleConflict(mergeTerminal(lock, view), view);
    }

    /** Publish the view's current tip and frontier clock to a lock state (a snapshot copy of the clock). */
    private static void publish(LockState state, BranchingView view) {
        state.commit = view.head();
        state.clock = new VectorClock(view.clock());
    }

    /**
     * If {@code lock} is a thread object that has ended, merge its terminal tip into this view (join
     * semantics); otherwise a no-op. Idempotent: once merged, the terminal tip is already in this
     * view's clock and {@link BranchingView#merge} short-circuits. Never mutates {@link #terminalHeads}.
     */
    private VersionedLocation mergeTerminal(StaticObject lock, BranchingView view) {
        Tip terminal = terminalHeads.get(lock);
        return terminal == null ? null : view.merge(terminal.head(), terminal.clock());
    }

    @Override
    public void onVolatileRead() {
        // Volatiles are a later branching milestone; treated as ordinary reads until then.
    }

    @Override
    public void onVolatileWrite() {
        // Volatiles are a later branching milestone; treated as ordinary writes until then.
    }

    private void handleConflict(VersionedLocation conflict, BranchingView view) {
        if (conflict == null) {
            return;
        }
        // Record a pending conflict, raised as a guest exception at the next safe point (top of the
        // bytecode loop, past any just-executed monitorexit), or by GuestRunnable if this was the
        // thread's final sync. We must NOT throw from inside the monitor op itself: that corrupts the
        // synchronized block's exit machinery. The conflicted merge commit already advanced head, so
        // the branch stays consistent if the conflict is caught and execution continues.
        view.setPendingConflict("Sync-as-Share conflict: incompatible concurrent update to " + describe(conflict));
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
