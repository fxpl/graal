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

import java.util.BitSet;

/**
 * The acting thread's working copy of a single app-owned array (Sync-as-Share, stage-on-access).
 *
 * <p>{@code host} is a <em>real</em> host array of the array's exact component type ({@code byte[]},
 * {@code int[]}, {@code StaticObject[]}, ...), materialised once from the thread's staged-or-committed
 * elements over the physical (version-0) array and then cached in the thread's {@link MemoryView}. It
 * is the single representation the thread uses for the array: bytecode element access ({@code
 * aaload}/{@code aastore} via {@link MemoryModel#readArray}/{@link MemoryModel#writeArray}), the
 * {@code unwrap} / native / {@code arraycopy} path (via {@link MemoryModel#arrayView}), and bulk writes
 * all read and write this one array — so an {@code arraycopy} into it simply lands, no synthesis, no
 * throwaway. This replaces the earlier {@code arrayWorkingCopy} projection that rebuilt a fresh array on
 * every {@code unwrap} and therefore discarded writes made into it.
 *
 * <p>{@code touched} records the indices this thread has <em>written</em> (an {@code aastore} index, an
 * {@code arraycopy} destination range). It is the per-array write set: at the next synchronisation the
 * view flushes exactly these indices to per-index {@link ArrayLocation} staging, so the existing
 * FastTrack / vector-clock merge sees precisely the elements this thread changed — the per-index
 * granularity that keeps disjoint-index writers from being a false conflict. Reads do not touch it.
 *
 * <p>Owned by one guest thread (lives on its view); no synchronisation of its own.
 */
final class ArrayVersion {

    /** The thread's working array; the same object {@code unwrap} returns and {@code arraycopy} writes. */
    final Object host;

    /** Indices this thread has written since the copy was materialised (the per-array write set). */
    final BitSet touched = new BitSet();

    ArrayVersion(Object host) {
        this.host = host;
    }
}
