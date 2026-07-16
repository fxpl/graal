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
 * <p>Implementations are behaviourally equivalent but differ in <em>concurrency strategy</em>, chosen
 * at context creation by {@code --java.MemoryModelHistory} (a design-exploration knob; see
 * concurrency-design.md):
 * <ul>
 *   <li>{@link GlobalVersionStore} — {@code rwlock}: a read/write-split single lock. Pulls and
 *       empty-delta pushes take the read lock (concurrent); committing pushes take the write lock.</li>
 *   <li>{@link WatermarkHistory} — {@code watermark}: per-location stripe locks plus a commit-order
 *       watermark. Disjoint committing pushes run in parallel; only an O(1) retire is serial.</li>
 * </ul>
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
}
