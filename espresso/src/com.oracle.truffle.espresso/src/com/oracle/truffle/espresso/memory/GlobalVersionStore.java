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

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The {@link GlobalHistory} implementation: a monotonic {@code counter} plus a per-location
 * append-only version chain, guarded by a read/write-split lock.
 *
 * <p><b>Concurrency (M2).</b> Unlike the prototype (single-threaded under a model checker), Espresso
 * runs real threads. {@link #pull}/{@link #push} conflict-check and (for a committing push) append,
 * so they synchronise on {@code g} — but only sync points touch {@code g}; ordinary reads/writes stay
 * thread-local (the point of the model). The lock is a {@link ReentrantReadWriteLock}, split by what
 * the op actually does to {@code g}:
 * <ul>
 *   <li><b>pull</b> and an <b>empty-delta push</b> only <em>read</em> {@code g} (conflict-check the
 *       heads, read the counter) and then advance the caller's own view. They take the <em>read</em>
 *       lock, so any number of them run concurrently — a read-only critical section (enter = pull,
 *       exit = empty push) no longer serialises against unrelated ones.</li>
 *   <li>a <b>committing push</b> (non-empty staging) appends to {@code g}, so it takes the
 *       <em>write</em> lock and excludes all readers. Its hold time is O(delta) — inherent to the
 *       single global tick; removing it needs per-location locking / the commit-watermark redesign
 *       (see concurrency-design.md).</li>
 * </ul>
 * Snapshot reads ({@link #snapshot}) are <em>lock-free</em> and never touch the read/write lock at
 * all: each location maps to the newest {@link Version} node of an immutable {@code prev}-linked
 * chain, so a reader observes a fully built chain via {@code final}-field safe publication and never a
 * torn structure, even concurrently with a committing push. Appending is O(1) (a new head node); the
 * {@link ConcurrentHashMap} is retained precisely because its {@code get} is genuinely concurrent with
 * a write-locked {@code put} (and is O(1)) — unlike the previous {@code CopyOnWriteArrayList} value,
 * whose per-append full copy made a hot location's pushes quadratic.
 */
public final class GlobalVersionStore implements GlobalHistory {

    // Value = newest version of the location (chain head); older versions via Version.prev(). null =
    // no version yet. Mutated only under the write lock; read lock-free by snapshot, read-locked by
    // firstConflict.
    private final ConcurrentHashMap<FieldLocation, Version> history = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong(0);
    // writesThrough[c] = total writes published by the pushes numbered 1..c, so the writes a pull brings
    // in are writesThrough[new base] - writesThrough[old base]. Written under the write lock, read under
    // the read lock. One long per committing push.
    private long[] writesThrough = new long[64];
    private final ReentrantReadWriteLock gLock = new ReentrantReadWriteLock();

    @Override
    public long currentCounter() {
        return counter.get();
    }

    /**
     * Newest value visible at {@code base} (the newest version with {@code counter <= base}), or
     * {@link MemoryModel#ABSENT} if the location has no version at or before {@code base} (caller
     * reads through to the real heap — the pre-main state is the implicit initial committed version).
     */
    @Override
    public Object snapshot(FieldLocation loc, long base) {
        // Walk the immutable chain newest-first; the first node at or below base is the visible value.
        for (Version v = history.get(loc); v != null; v = v.prev()) {
            if (v.counter() <= base) {
                return v.value();
            }
        }
        return MemoryModel.ABSENT;
    }

    /**
     * Pull: conflict-check the view's staged writes against {@code g}; on success advance the view's
     * base to the current counter and return {@code null}. On conflict, return the offending location
     * and leave the view unchanged.
     */
    @Override
    public FieldLocation pull(LinearLocalView view) {
        // Read-only over g: conflict-check heads, read the counter, advance the caller's own view.
        // The read lock excludes only committing pushes; concurrent pulls/empty-pushes run in parallel.
        Lock read = gLock.readLock();
        read.lock();
        try {
            FieldLocation conflict = firstConflict(view);
            if (conflict != null) {
                view.setLastPulledWrites(0);
                return conflict;
            }
            long now = counter.get();
            view.setLastPulledWrites((int) (writesThrough[(int) now] - writesThrough[(int) view.base()]));
            view.advanceBase(now);
            return null;
        } finally {
            read.unlock();
        }
    }

    /**
     * Push: conflict-check, then (if there are staged writes) append them as a new version, clear
     * staging, and rebase. Returns {@code null} on success, or the offending location on conflict
     * (view left unchanged).
     */
    @Override
    public FieldLocation push(LinearLocalView view) {
        // An empty-delta push publishes nothing -- it only fast-forwards this thread's base and cannot
        // conflict (no staged locations) -- so it does not mutate g and shares the read lock with pulls.
        // (Safe to test stagingEmpty() outside the lock: the view is owned by the calling thread.)
        if (view.stagingEmpty()) {
            Lock read = gLock.readLock();
            read.lock();
            try {
                view.advanceBase(counter.get());
                return null;
            } finally {
                read.unlock();
            }
        }
        // Committing push: appends to g, so it needs the exclusive write lock.
        Lock write = gLock.writeLock();
        write.lock();
        try {
            FieldLocation conflict = firstConflict(view);
            if (conflict != null) {
                return conflict;
            }
            long next = counter.incrementAndGet();
            if (next >= writesThrough.length) {
                writesThrough = Arrays.copyOf(writesThrough, writesThrough.length * 2);
            }
            writesThrough[(int) next] = writesThrough[(int) next - 1] + view.staging().size();
            for (Map.Entry<FieldLocation, Object> e : view.staging().entrySet()) {
                // New head whose prev is the old head (null if first): O(1), no copy.
                history.compute(e.getKey(), (k, old) -> new Version(next, e.getValue(), old));
                VersioningStats.onGVersion(history.size());
            }
            view.clearStaging();
            view.advanceBase(next);
            return null;
        } finally {
            write.unlock();
        }
    }

    /**
     * The first staged location whose latest global version is newer than the view's base (i.e. an
     * unseen update to a location this thread has also changed), or {@code null} if none. Must be
     * called while holding {@code gLock} (read lock suffices — it only reads history heads); a
     * committing push holds the write lock so its subsequent append sees the same checked state.
     */
    private FieldLocation firstConflict(LinearLocalView view) {
        long base = view.base();
        for (FieldLocation loc : view.staging().keySet()) {
            Version head = history.get(loc);   // newest version; null if none
            if (head != null && head.counter() > base) {
                return loc;
            }
        }
        return null;
    }

    /**
     * Reclaim every version strictly older than each location's floor — the newest version with
     * {@code counter <= threshold}. No live thread (all with base {@code >= threshold}) can resolve
     * below that floor, so the dropped tail is unreachable-in-future.
     *
     * <p>Nodes are immutable (a {@code record} with a {@code final prev}); rather than mutate the
     * chain — which would break the lock-free reader's {@code final}-field safety — we <em>rebuild</em>
     * the retained prefix {@code head..floor} onto a fresh floor whose {@code prev} is {@code null},
     * then republish the new head via the {@link ConcurrentHashMap}. An in-flight lock-free reader
     * that already holds the old head keeps walking the old (still-intact) chain and resolves
     * correctly; a reader arriving after the {@code put} sees the new head via the map's
     * happens-before. Once the old head is unreferenced its whole tail (including the dropped part) is
     * collectable. Rebuild cost is O(versions above the floor) — small when collapse runs often; the
     * reclaimed tail below the floor is what we drop. Runs under the write lock so no committing push
     * mutates a chain concurrently.
     */
    @Override
    public void collapse(long threshold) {
        Lock write = gLock.writeLock();
        write.lock();
        try {
            for (Map.Entry<FieldLocation, Version> e : history.entrySet()) {
                Version head = e.getValue();
                // Floor = newest version with counter <= threshold.
                Version floor = head;
                while (floor != null && floor.counter() > threshold) {
                    floor = floor.prev();
                }
                if (floor == null || floor.prev() == null) {
                    // Whole chain is newer than the threshold (nothing settled), or the floor is
                    // already the oldest node: nothing to drop.
                    continue;
                }
                if (VersioningStats.ENABLED) {
                    long dropped = 0;
                    for (Version v = floor.prev(); v != null; v = v.prev()) {
                        dropped++;
                    }
                    VersioningStats.onCollapse(dropped);
                }
                Version newFloor = new Version(floor.counter(), floor.value(), null);
                if (head == floor) {
                    e.setValue(newFloor);   // cold location fully settled below the threshold: O(1)
                } else {
                    // Rebuild head..(floor-exclusive) top-down, then re-link bottom-up onto newFloor.
                    java.util.ArrayList<Version> above = new java.util.ArrayList<>();
                    for (Version v = head; v != floor; v = v.prev()) {
                        above.add(v);
                    }
                    Version rebuilt = newFloor;
                    for (int i = above.size() - 1; i >= 0; i--) {
                        Version v = above.get(i);
                        rebuilt = new Version(v.counter(), v.value(), rebuilt);
                    }
                    e.setValue(rebuilt);
                }
            }
        } finally {
            write.unlock();
        }
    }

    /**
     * Squash: keep, per location, only the versions some live base resolves to (the newest version
     * {@code <= b} for each base {@code b}) plus the head, dropping the shadowed interior. A version
     * {@code v} with newer-neighbour counter {@code n} (or {@code +inf} for the head) resolves a base
     * {@code b} iff {@code v.counter <= b < n}; it is kept iff it is the head or resolves some base.
     * Same immutable-rebuild + write-lock discipline as {@link #collapse(long)}.
     */
    @Override
    public void squash(long[] bases) {
        if (bases.length == 0) {
            return;
        }
        Lock write = gLock.writeLock();
        write.lock();
        try {
            for (Map.Entry<FieldLocation, Version> e : history.entrySet()) {
                Version head = e.getValue();
                // Kept versions, newest-first. The head is always kept (conflict detection).
                java.util.ArrayList<Version> kept = new java.util.ArrayList<>();
                long dropped = 0;
                long newerCounter = Long.MAX_VALUE;   // counter of v's newer neighbour in the real chain
                for (Version v = head; v != null; v = v.prev()) {
                    if (v == head || resolvesSomeBase(bases, v.counter(), newerCounter)) {
                        kept.add(v);
                    } else {
                        dropped++;
                    }
                    newerCounter = v.counter();
                }
                if (dropped == 0) {
                    continue;
                }
                if (VersioningStats.ENABLED) {
                    VersioningStats.onCollapse(dropped);
                }
                // Rebuild bottom-up (oldest kept first) so prev links point at the older kept node.
                Version rebuilt = null;
                for (int i = kept.size() - 1; i >= 0; i--) {
                    Version v = kept.get(i);
                    rebuilt = new Version(v.counter(), v.value(), rebuilt);
                }
                e.setValue(rebuilt);
            }
        } finally {
            write.unlock();
        }
    }

    /** Whether some base {@code b} falls in {@code [lo, hi)} — i.e. a live thread resolves to this version. */
    private static boolean resolvesSomeBase(long[] bases, long lo, long hi) {
        for (long b : bases) {
            if (b >= lo && b < hi) {
                return true;
            }
        }
        return false;
    }
}
