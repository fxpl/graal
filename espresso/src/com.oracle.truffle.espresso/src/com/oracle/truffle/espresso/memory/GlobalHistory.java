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

/**
 * The single global version history {@code g} of the Linear model: a per-location append-only history
 * of {@link Version}s that threads communicate through at synchronisation points. Ordinary
 * reads/writes stay thread-local (in each thread's {@link LinearLocalView}); only sync actions touch
 * {@code g}, via {@link #pull} and {@link #push}, and ordinary read fall-through via {@link #snapshot}.
 *
 * <p>The implementation is {@link GlobalVersionStore}: a read/write-split single lock over {@code g}.
 * Pulls and empty-delta pushes take the read lock (concurrent); a committing push takes the write lock.
 * (This is an interface because the concurrency strategy was a design-exploration axis; the alternative
 * strategies have been dropped — see the paper's evaluation for the comparison.)
 */
public interface GlobalHistory {

    /**
     * Newest value visible at {@code base} (the newest version with {@code counter <= base}), or
     * {@link MemoryModel#ABSENT} if the location has no version at or before {@code base} (the caller
     * then reads through to the real heap — the pre-main state is the implicit initial committed
     * version).
     */
    Object snapshot(VersionedLocation loc, long base);

    /**
     * Pull: conflict-check the view's staged writes against the visible history and, on success,
     * advance the view's base to the current global position. Returns {@code null} on success, or the
     * offending location on conflict (leaving the view unchanged).
     */
    VersionedLocation pull(LinearLocalView view);

    /**
     * Push: conflict-check, then (if there are staged writes) publish them as a new global version,
     * clear staging, and rebase. Returns {@code null} on success, or the offending location on
     * conflict (leaving the view unchanged).
     */
    VersionedLocation push(LinearLocalView view);

    /** The current visible global position (the newest committed counter). */
    long currentCounter();

    /**
     * Collapse (reclaim) history older than {@code threshold}, the collapse threshold
     * {@code T = min} over live threads of their observed base. For each location the newest version
     * with {@code counter <= T} is retained (as a moving {@code t=0}) and every strictly-older version
     * is dropped, since no live thread — all with base {@code >= T} — can ever resolve to one. A no-op
     * by default (unbounded history); {@link GlobalVersionStore} implements it. Invoked on a cadence by
     * {@link LinearMemoryModel} only when {@code --java.MemoryModelCollapse=threshold}.
     */
    default void collapse(long threshold) {
        // Default: keep all history (MemoryModelCollapse=off, or a strategy without reclamation).
    }

    /**
     * Collapse (reclaim) using the full set of live thread bases (all {@code > 0}). For each location
     * the retained versions are exactly those some live base <em>resolves to</em> — the newest version
     * {@code <= b} for each base {@code b} — plus the head (kept for conflict detection). Every other
     * version is shadowed for every live thread and is dropped, including the interior between a
     * far-behind base and the head that {@link #collapse(long)} would keep. A no-op by default;
     * {@link GlobalVersionStore} implements it. Invoked on a cadence by {@link LinearMemoryModel} only
     * when {@code --java.MemoryModelCollapse=squash}.
     */
    default void squash(long[] bases) {
        // Default: keep all history.
    }
}
