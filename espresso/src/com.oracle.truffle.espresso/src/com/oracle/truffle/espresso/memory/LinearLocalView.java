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

import java.util.HashMap;
import java.util.Map;

import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * A thread's private view of memory (prototype: {@code LocalVersionStore}). Holds the staging area
 * {@code δ} (uncommitted writes) and the base counter — the position in the global history this
 * thread has observed. Owned by a single guest thread (lives on its {@code EspressoThreadLocalState}),
 * so it needs no synchronisation of its own.
 */
public final class LinearLocalView implements MemoryView {

    // Volatile: the owning thread advances it (only ever forward, under g's lock), but the collapse
    // trigger reads it cross-thread to compute the min-base threshold. A stale-low read is a safe
    // lower bound (we would retain more, never drop something a thread still needs).
    private volatile long base;
    private final Map<VersionedLocation, Object> staging = new HashMap<>();
    // Sync-as-Share (stage-on-access): per-app-owned-array working copy, materialised on first access and
    // reused for reads/writes/unwrap this epoch. Flushed (touched indices only) to per-index staging before
    // each push/pull and cleared after, so it rebuilds from the freshly-observed base next epoch.
    private final Map<StaticObject, ArrayVersion> arrays = new HashMap<>();
    // Sync-as-Share (stage-on-access): lazily captured genesis (version-0) field values, so a versioned
    // read never falls through to the live physical slot more than once. A read cache, not a write set:
    // never pushed, shadowed by any committed value (read consults it only after staging and the base
    // snapshot). The physical genesis is immutable, so entries never go stale.
    private final Map<VersionedLocation, Object> captured = new HashMap<>();
    // A conflict detected during a synchronisation action, to be raised at the next safe point (the
    // top of the bytecode loop) rather than from inside the sync op. Null if none pending.
    private String pendingConflict;

    public long base() {
        return base;
    }

    public void advanceBase(long counter) {
        this.base = counter;
    }

    /** Staged value for {@code loc}, or {@code null} if not staged (staged values are never null). */
    public Object getStaged(VersionedLocation loc) {
        return staging.get(loc);
    }

    /** Lazily-captured genesis value for {@code loc}, or {@code null} if none captured. See {@link #captured}. */
    public Object getCaptured(VersionedLocation loc) {
        return captured.get(loc);
    }

    /** Cache a lazily-captured genesis field value (a read, never pushed). See {@link #captured}. */
    public void captureRead(VersionedLocation loc, Object value) {
        captured.put(loc, value);
    }

    public void stage(VersionedLocation loc, Object value) {
        staging.put(loc, value);
        VersioningStats.observeStaging(staging.size());
    }

    // --- Sync-as-Share array working copies (stage-on-access) ---

    /** The acting thread's cached working copy of {@code array}, or {@code null} if not yet materialised. */
    ArrayVersion arrayVersion(StaticObject array) {
        return arrays.get(array);
    }

    /** Cache a freshly materialised working copy of {@code array}. */
    void putArrayVersion(StaticObject array, ArrayVersion av) {
        arrays.put(array, av);
    }

    /**
     * Fold each array working copy's touched indices into per-index {@link ArrayLocation} staging, so the
     * push/pull conflict check and publication see exactly the elements this thread wrote. Reads (untouched
     * copies) flush nothing. Called just before a push or pull.
     */
    public void flushArrays() {
        for (Map.Entry<StaticObject, ArrayVersion> e : arrays.entrySet()) {
            StaticObject array = e.getKey();
            ArrayVersion av = e.getValue();
            for (int i = av.touched.nextSetBit(0); i >= 0; i = av.touched.nextSetBit(i + 1)) {
                stage(new ArrayLocation(array, i), java.lang.reflect.Array.get(av.host, i));
            }
        }
    }

    /** Drop all working copies; they rebuild from the freshly-observed base on next access. */
    public void clearArrays() {
        arrays.clear();
    }

    public Map<VersionedLocation, Object> staging() {
        return staging;
    }

    public boolean stagingEmpty() {
        return staging.isEmpty();
    }

    public int stagingSize() {
        return staging.size();
    }

    public void clearStaging() {
        staging.clear();
    }

    public boolean hasPendingConflict() {
        return pendingConflict != null;
    }

    public void setPendingConflict(String description) {
        this.pendingConflict = description;
    }

    /** Returns the pending conflict description and clears it. */
    public String takePendingConflict() {
        String c = pendingConflict;
        pendingConflict = null;
        return c;
    }
}
