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
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The {@code watermark} {@link GlobalHistory} strategy ({@code --java.MemoryModelHistory=watermark}):
 * per-location stripe locks plus a commit-order watermark (see concurrency-design.md, option 3).
 *
 * <p>Two constraints shape it: Linear needs one global total order (so a single monotonic tick), and a
 * sync interval's writes must publish atomically (a reader sees all of a commit or none). The tick is
 * split for concurrency:
 * <ul>
 *   <li>{@code allocated} hands each committing push a unique tick {@code N} — the total order.</li>
 *   <li>{@code committed} (the <em>watermark</em>, {@code volatile}) is the highest tick {@code T}
 *       with every tick {@code <= T} fully appended. Readers use it as their base and {@link #snapshot}
 *       only returns versions with {@code counter <= base}, so a reader never sees a half-written
 *       commit — atomic publication falls out of the watermark and readers stay lock-free.</li>
 * </ul>
 *
 * <p>A committing push locks only the <em>stripes</em> of its staged locations (fixed
 * {@link ReentrantLock} array, {@code stripe(loc) = locks[hash(loc) mod K]}), in index order to be
 * deadlock-free; it conflict-checks, allocates {@code N} (only after the check passes, so there are no
 * gaps in the tick sequence), appends {@code @N} to each chain, releases the stripes, then retires the
 * tick in order. Disjoint-location pushes share no stripe and run in parallel; same-location pushes
 * serialise on that stripe (the real conflict set). The only global-serial work is the O(1) retire.
 */
public final class WatermarkHistory implements GlobalHistory {

    private static final int STRIPES = 256;

    // Value = newest version of the location (chain head); older via Version.prev(). null = none.
    // Mutated only under the location's stripe lock; read lock-free by snapshot and by pull.
    private final ConcurrentHashMap<LinearLocation, Version> history = new ConcurrentHashMap<>();
    private final AtomicLong allocated = new AtomicLong(0);   // hands out unique ticks (total order)
    private volatile long committed = 0;                      // watermark: all ticks <= this are visible
    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];
    private final Object retireMonitor = new Object();        // orders the O(1) watermark advance

    public WatermarkHistory() {
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    /** The current visible global position — the committed watermark. */
    @Override
    public long currentCounter() {
        return committed;
    }

    private static int stripeIndex(LinearLocation loc) {
        return (loc.hashCode() & 0x7fffffff) % STRIPES;
    }

    @Override
    public Object snapshot(LinearLocation loc, long base) {
        // Walk the immutable chain newest-first; the first node at or below base is the visible value.
        for (Version v = history.get(loc); v != null; v = v.prev()) {
            if (v.counter() <= base) {
                return v.value();
            }
        }
        return MemoryModel.ABSENT;
    }

    /**
     * Pull: lock-free. Import history up to the current watermark; on success advance the view's base
     * to it. Conflict if a staged location has a committed update newer than the view's base; an
     * in-flight tick above the watermark is invisible here and is caught at this thread's own push.
     */
    @Override
    public LinearLocation pull(LinearLocalView view) {
        long t = committed;   // volatile read: the visible watermark
        LinearLocation conflict = firstConflict(view, t);
        if (conflict != null) {
            return conflict;
        }
        view.advanceBase(t);
        return null;
    }

    @Override
    public LinearLocation push(LinearLocalView view) {
        // Empty-delta push: publishes nothing and cannot conflict; just adopt the current watermark.
        // Lock-free (the view is owned by the calling thread, so stagingEmpty() needs no lock).
        if (view.stagingEmpty()) {
            view.advanceBase(committed);
            return null;
        }
        // Committing push: lock the staged locations' stripes (index order = deadlock-free), then
        // conflict-check and append under them. Bound MAX_VALUE: any appended head newer than base is a
        // conflict, committed or in-flight (every allocated tick will retire, so an in-flight newer
        // version is a real earlier commit in the total order).
        int[] idx = distinctSortedStripes(view);
        lockAll(idx);
        long n;
        try {
            LinearLocation conflict = firstConflict(view, Long.MAX_VALUE);
            if (conflict != null) {
                return conflict;   // no tick allocated -> no gap in the sequence
            }
            n = allocated.incrementAndGet();
            for (Map.Entry<LinearLocation, Object> e : view.staging().entrySet()) {
                // New head whose prev is the old head (null if first): O(1), no copy.
                history.compute(e.getKey(), (k, old) -> new Version(n, e.getValue(), old));
            }
        } finally {
            unlockAll(idx);
        }
        // Retire in order: make tick n visible only after n-1, and not until then set our own base to n
        // (self-consistency: base=n must see our @n writes and everything < n, i.e. all <= n appended).
        retire(n);
        view.clearStaging();
        view.advanceBase(n);
        return null;
    }

    /**
     * Advance the watermark to {@code n}, strictly after {@code n-1}. Ticks are gap-free (allocated
     * only on a committing push that will append), so this ordered hand-off always drains: the lowest
     * outstanding tick has its predecessor already committed. The wait forms a chain (n waits on n-1),
     * never a cycle — a thread here holds no stripe lock or guest monitor.
     */
    private void retire(long n) {
        boolean interrupted = false;
        synchronized (retireMonitor) {
            while (committed != n - 1) {
                try {
                    retireMonitor.wait();
                } catch (InterruptedException e) {
                    // Must finish retiring or successors stall forever; remember and restore below.
                    interrupted = true;
                }
            }
            committed = n;                 // volatile publish of tick n
            retireMonitor.notifyAll();     // wake the thread waiting for n (and re-check the rest)
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The first staged location whose newest version at or below {@code upperBound} is newer than the
     * view's base (an unseen update to a location this thread also changed), or {@code null} if none.
     * {@code upperBound} is the watermark for a lock-free pull (ignore in-flight ticks) and
     * {@link Long#MAX_VALUE} for a committing push holding the locations' stripes (see any appended
     * head).
     */
    private LinearLocation firstConflict(LinearLocalView view, long upperBound) {
        long base = view.base();
        for (LinearLocation loc : view.staging().keySet()) {
            for (Version v = history.get(loc); v != null; v = v.prev()) {
                if (v.counter() <= upperBound) {
                    if (v.counter() > base) {
                        return loc;
                    }
                    break;   // newest version at/below the bound is <= base: no conflict here
                }
            }
        }
        return null;
    }

    private int[] distinctSortedStripes(LinearLocalView view) {
        TreeSet<Integer> s = new TreeSet<>();
        for (LinearLocation loc : view.staging().keySet()) {
            s.add(stripeIndex(loc));
        }
        int[] a = new int[s.size()];
        int i = 0;
        for (int x : s) {
            a[i++] = x;
        }
        return a;
    }

    private void lockAll(int[] idx) {
        for (int i : idx) {
            stripes[i].lock();
        }
    }

    private void unlockAll(int[] idx) {
        for (int i = idx.length - 1; i >= 0; i--) {
            stripes[idx[i]].unlock();
        }
    }
}
