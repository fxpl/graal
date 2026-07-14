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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The single global version history {@code g} of the Linear model (prototype: {@code
 * GlobalVersionStore}). A monotonic {@code counter} plus a per-location append-only version list.
 *
 * <p><b>Concurrency (M2).</b> Unlike the prototype (single-threaded under a model checker), Espresso
 * runs real threads. {@link #pull}/{@link #push} — which conflict-check and (for push) append — run
 * under a single {@link ReentrantLock}, so all synchronisation actions serialise on {@code g}. That
 * is acceptable because ordinary reads/writes stay thread-local (the point of the model); only sync
 * points touch {@code g}. Snapshot reads ({@link #snapshot}) are lock-free over the append-only
 * {@link CopyOnWriteArrayList}. A lock-free {@code g} is a later optimisation; the interface is kept
 * narrow so it can drop in.
 */
public final class GlobalVersionStore {

    private final ConcurrentHashMap<LinearLocation, CopyOnWriteArrayList<Version>> history = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong(0);
    private final ReentrantLock pushLock = new ReentrantLock();

    public long currentCounter() {
        return counter.get();
    }

    /**
     * Newest value visible at {@code base} (the newest version with {@code counter <= base}), or
     * {@link MemoryModel#ABSENT} if the location has no version at or before {@code base} (caller
     * reads through to the real heap — the pre-main state is the implicit initial committed version).
     */
    public Object snapshot(LinearLocation loc, long base) {
        CopyOnWriteArrayList<Version> h = history.get(loc);
        if (h != null) {
            for (int i = h.size() - 1; i >= 0; i--) {
                Version v = h.get(i);
                if (v.counter() <= base) {
                    return v.value();
                }
            }
        }
        return MemoryModel.ABSENT;
    }

    /**
     * Pull: conflict-check the view's staged writes against {@code g}; on success advance the view's
     * base to the current counter and return {@code null}. On conflict, return the offending location
     * and leave the view unchanged.
     */
    public LinearLocation pull(LinearLocalView view) {
        pushLock.lock();
        try {
            LinearLocation conflict = firstConflict(view);
            if (conflict != null) {
                return conflict;
            }
            view.advanceBase(counter.get());
            return null;
        } finally {
            pushLock.unlock();
        }
    }

    /**
     * Push: conflict-check, then (if there are staged writes) append them as a new version, clear
     * staging, and rebase. Returns {@code null} on success, or the offending location on conflict
     * (view left unchanged).
     */
    public LinearLocation push(LinearLocalView view) {
        pushLock.lock();
        try {
            LinearLocation conflict = firstConflict(view);
            if (conflict != null) {
                return conflict;
            }
            if (view.stagingEmpty()) {
                view.advanceBase(counter.get());
            } else {
                long next = counter.incrementAndGet();
                for (Map.Entry<LinearLocation, Object> e : view.staging().entrySet()) {
                    history.computeIfAbsent(e.getKey(), k -> new CopyOnWriteArrayList<>()).add(new Version(next, e.getValue()));
                }
                view.clearStaging();
                view.advanceBase(next);
            }
            return null;
        } finally {
            pushLock.unlock();
        }
    }

    /**
     * The first staged location whose latest global version is newer than the view's base (i.e. an
     * unseen update to a location this thread has also changed), or {@code null} if none. Must be
     * called while holding {@link #pushLock}.
     */
    private LinearLocation firstConflict(LinearLocalView view) {
        long base = view.base();
        for (LinearLocation loc : view.staging().keySet()) {
            CopyOnWriteArrayList<Version> h = history.get(loc);
            if (h != null && !h.isEmpty() && h.get(h.size() - 1).counter() > base) {
                return loc;
            }
        }
        return null;
    }
}
