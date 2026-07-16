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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The {@code rwlock} {@link GlobalHistory} strategy (default; {@code --java.MemoryModelHistory=rwlock}).
 * A monotonic {@code counter} plus a per-location append-only version chain.
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
    private final ConcurrentHashMap<VersionedLocation, Version> history = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong(0);
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
    public Object snapshot(VersionedLocation loc, long base) {
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
    public VersionedLocation pull(LinearLocalView view) {
        // Read-only over g: conflict-check heads, read the counter, advance the caller's own view.
        // The read lock excludes only committing pushes; concurrent pulls/empty-pushes run in parallel.
        Lock read = gLock.readLock();
        read.lock();
        try {
            VersionedLocation conflict = firstConflict(view);
            if (conflict != null) {
                return conflict;
            }
            view.advanceBase(counter.get());
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
    public VersionedLocation push(LinearLocalView view) {
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
            VersionedLocation conflict = firstConflict(view);
            if (conflict != null) {
                return conflict;
            }
            long next = counter.incrementAndGet();
            for (Map.Entry<VersionedLocation, Object> e : view.staging().entrySet()) {
                // New head whose prev is the old head (null if first): O(1), no copy.
                history.compute(e.getKey(), (k, old) -> new Version(next, e.getValue(), old));
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
    private VersionedLocation firstConflict(LinearLocalView view) {
        long base = view.base();
        for (VersionedLocation loc : view.staging().keySet()) {
            Version head = history.get(loc);   // newest version; null if none
            if (head != null && head.counter() > base) {
                return loc;
            }
        }
        return null;
    }
}
