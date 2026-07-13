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
 * Per-context memory-model service for Sync-as-Share.
 *
 * <p>A {@code MemoryModel} mediates ordinary shared-memory accesses and synchronisation actions.
 * The stock, sequentially-consistent behaviour is provided by {@link SharedMemoryModel}, which is
 * the identity: it is never consulted on the interpreter hot path because the hooks are guarded by
 * {@link com.oracle.truffle.espresso.EspressoLanguage#isMemoryModelEnabled()}, a partial-evaluation
 * constant that is {@code false} under {@code --java.MemoryModel=shared}, so the entire hook folds
 * away and the compiled code is identical to stock Espresso.
 *
 * <p>The Linear version-controlled memory model (paper: Sync-as-Share, &sect;3.1/&sect;4.3) is
 * introduced at milestone M2 and will extend this interface with the ordinary field/array access
 * operations and the synchronisation actions of Table 1 (lock/unlock, spawn/join, start/end,
 * volatile read/write). M1 only establishes the seam and proves the {@code shared} control has zero
 * overhead.
 */
public interface MemoryModel {

    /**
     * Whether this model changes shared-memory semantics. {@code false} for the stock
     * {@link SharedMemoryModel}; {@code true} for version-controlled models. Mirrors
     * {@link com.oracle.truffle.espresso.EspressoLanguage#isMemoryModelEnabled()}.
     */
    boolean isEnabled();
}
