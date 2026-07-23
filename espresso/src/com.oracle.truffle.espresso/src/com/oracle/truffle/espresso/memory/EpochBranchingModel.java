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
 * The <em>epoch</em> backing of the Sync-as-Share Branching model — selected by
 * {@code --java.MemoryModel=branching --java.BranchingBacking=epoch}. It is <b>the same model</b> as
 * {@link BranchingMemoryModel} (the commit-DAG backing): identical accept/reject in every case, by
 * construction (the conflict rule is the same). It differs only in <b>representation</b>, and hence
 * performance.
 *
 * <p>Where the DAG backing hands each sync object a {@link Commit} (a point in time) and recovers "what
 * was written since I last looked" by <em>walking</em> the divergence (O(divergence)), this backing
 * gives each sync object a per-location {@link EpochLockState#table} — location &rarr; latest writer
 * {@link EpochEntry} — and recovers the same answer by a <em>lookup</em> (O(working set)). There is no
 * DAG: causality is the {@link VectorClock} (as it already was once the LCA was dropped), values live
 * in the epoch entries, and epochs are minted from a per-thread counter. This removes the contended
 * lock's walk (branching's weak case) while keeping disjoint locks independent (each has its own small
 * table — no global index, so it does not become the linear model). See epoch-model.md.
 *
 * <p>The synchronisation protocol mirrors {@link BranchingMemoryModel} action-for-action — spawn forks,
 * enter merges + republishes, exit republishes, end records a terminal tip, join merges the terminal
 * tip at the joiner's monitor op (via a separate {@code terminalTips} map for the same reason the DAG
 * backing keeps {@code terminalHeads} separate). Only the payload changes: a published tip is a
 * {@code (table, clock)} snapshot instead of a {@code (commit, clock)} one, and "merge" is
 * {@link EpochView#mergeTable} instead of a DAG walk.
 */
public final class EpochBranchingModel implements MemoryModel {

    private final EspressoContext context;
    private volatile boolean active = false;
    private final AtomicLong threadIds = new AtomicLong();

    /** Per-monitor (and per-thread-object, for join) published table + clock. Keyed by object identity. */
    private final Map<StaticObject, EpochLockState> lockStates = new ConcurrentHashMap<>();

    /**
     * Per-volatile-field published tip — the paper's map {@code V} of the latest commit of each volatile
     * variable (§5.3 / Table 2), realised as the DAG backing's per-field tip. Keyed by
     * {@link FieldLocation}. Separate from {@link #lockStates}: a volatile field is its own sync object.
     */
    private final Map<FieldLocation, EpochLockState> volatileTips = new ConcurrentHashMap<>();

    /** Fork points recorded at spawn, keyed by the child's guest {@code Thread}; consumed at its start. */
    private final Map<StaticObject, Published> pendingForks = new ConcurrentHashMap<>();

    /**
     * Terminal tips of ended threads, keyed by the guest {@code Thread} object, for join. Write-once by
     * the ending thread at {@link #onThreadEnd}; read (merged, never overwritten) by a joiner's monitor
     * op. Kept separate from {@link #lockStates} so a joiner's own enter/exit republish cannot clobber
     * the joinee's published tip — see {@link BranchingMemoryModel} class doc for the full rationale.
     *
     * <p><b>Unbounded retention (a fork-join-accumulate chain is O(threads²)).</b> Never removed, and —
     * measured — weak keys on the {@code Thread} object do <em>not</em> help: the model versions a
     * constructed thread's own fields, so the constructor's {@code seen} pins every {@code Thread} it
     * makes (an ended, dereferenced thread is not collectable under this model), so the weak key never
     * clears. The deeper cause is epoch's copy representation: a forked child <em>adopts a copy</em> of
     * the parent's accumulated {@code seen}, and each terminal is a <em>copy</em> of the ended thread's
     * {@code seen}, so K forks each carrying O(K) state give O(K²) (OOM by ~1000 threads; the dag
     * backing stays linear because its terminal is a shared commit, not a copy). The real fixes are
     * larger — don't copy the whole {@code seen} on fork (share it copy-on-write), and prune settled /
     * dead-object locations from {@code seen} (§6.5) — neither a quick change. See epoch-model.md.
     */
    private final Map<StaticObject, Published> terminalTips = new ConcurrentHashMap<>();

    /** A published branch tip: a snapshot of a view's committed cache and its frontier clock. */
    private record Published(Map<VersionedLocation, EpochEntry> seen, VectorClock clock) {
    }

    public EpochBranchingModel(EspressoContext context) {
        this.context = context;
        VersioningStats.arm();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public MemoryView newThreadView() {
        return new EpochView(threadIds.getAndIncrement());
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void activate() {
        active = true;
    }

    private EpochView currentView() {
        // Safe: under this backing the thread-local view was created by this model's newThreadView().
        return (EpochView) context.getLanguage().getThreadLocalState().getMemoryView();
    }

    private EpochLockState lockState(StaticObject key) {
        return lockStates.computeIfAbsent(key, k -> new EpochLockState());
    }

    private EpochLockState volatileTip(FieldLocation key) {
        return volatileTips.computeIfAbsent(key, k -> new EpochLockState());
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
        StaticObject self = context.getCurrentPlatformThread();
        Published fork = pendingForks.remove(self);
        if (fork != null) {
            currentView().adopt(fork.seen(), fork.clock());
        }
    }

    @Override
    public void onThreadEnd() {
        // Commit the final staging and record the terminal tip for join, before the terminating notify
        // that wakes joiners, in a map monitor ops never overwrite. See BranchingMemoryModel class doc.
        EpochView view = currentView();
        view.commitStaging();
        terminalTips.put(context.getCurrentPlatformThread(), new Published(view.snapshotSeen(), view.publishClock()));
    }

    @Override
    public void onSpawn(StaticObject childThread) {
        // Fork: commit the parent's staging, then snapshot its cache + frontier for the child to adopt.
        EpochView view = currentView();
        view.commitStaging();
        pendingForks.put(childThread, new Published(view.snapshotSeen(), view.publishClock()));
    }

    @Override
    public void onMonitorEnter(StaticObject lock) {
        // Acquire = commit, merge the lock's published table, republish this holder's tip. Also merge a
        // terminated thread's tip if this monitor is a join target (see mergeTerminal).
        EpochView view = currentView();
        view.commitStaging();
        EpochLockState state = lockState(lock);
        VersionedLocation conflict = view.mergeTable(state.table, state.clock);
        publish(state, view);
        VersionedLocation joinConflict = mergeTerminal(lock, view);
        handleConflict(conflict != null ? conflict : joinConflict, view);
    }

    @Override
    public void onMonitorExit(StaticObject lock) {
        // Release = commit, republish this holder's tip to the lock. Also merge a terminated thread's
        // tip if this monitor is a join target: this is the point at which join reliably observes the
        // joinee's terminal tip (the wait-reacquire inside join fires no monitor hook).
        EpochView view = currentView();
        view.commitStaging();
        publish(lockState(lock), view);
        handleConflict(mergeTerminal(lock, view), view);
    }

    /**
     * Publish the view's whole committed cache and frontier to a lock state. Unlike the DAG backing's
     * O(1) head-pointer publish, this is O(the publisher's cache): a sync communicates the entire
     * branch tip, and the tip is materialised as a table here rather than a commit. The acquirer prunes
     * by its own clock, so publishing everything is correct (the publish/scan granularity is the
     * epoch backing's main performance knob — see epoch-model.md).
     */
    private static void publish(EpochLockState state, EpochView view) {
        state.table.putAll(view.seen());
        state.clock = view.publishClock();
    }

    /**
     * If {@code lock} is a thread object that has ended, merge its terminal tip into this view (join
     * semantics); otherwise a no-op. Idempotent: once merged, the terminal tip's epochs are in this
     * view's clock and {@link EpochView#mergeTable} skips them. Never mutates {@link #terminalTips}.
     */
    private VersionedLocation mergeTerminal(StaticObject lock, EpochView view) {
        Published terminal = terminalTips.get(lock);
        return terminal == null ? null : view.mergeTable(terminal.seen(), terminal.clock());
    }

    @Override
    public Object onVolatileRead(StaticObject holder, Field field) {
        // TODO: volatiles under the epoch backing are not implemented (deprioritised — the epoch backing
        // is secondary). Volatiles are supported under the dag backing; select --java.BranchingBacking=dag.
        throw new UnsupportedOperationException("volatile fields are not implemented under the branching epoch backing (TODO); use --java.BranchingBacking=dag");
    }

    @Override
    public void onVolatileWrite(StaticObject holder, Field field, Object value) {
        // TODO: volatiles under the epoch backing are not implemented (see onVolatileRead).
        throw new UnsupportedOperationException("volatile fields are not implemented under the branching epoch backing (TODO); use --java.BranchingBacking=dag");
    }

    private void handleConflict(VersionedLocation conflict, EpochView view) {
        if (conflict == null) {
            return;
        }
        // Record a pending conflict, raised at the next safe point (see BranchingMemoryModel). The
        // frontier was already advanced by the merge, so the branch stays consistent if it is caught.
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
