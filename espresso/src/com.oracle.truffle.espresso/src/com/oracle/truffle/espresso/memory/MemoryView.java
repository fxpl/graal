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
 * A guest thread's private Sync-as-Share memory view. The concrete shape is model-specific — Linear's
 * {@link LinearLocalView} is a staging map plus a scalar base cursor; Branching's is a staging map
 * plus a commit-DAG head — so the per-thread state is created by the active model via
 * {@link MemoryModel#newThreadView()}.
 *
 * <p>This interface exposes only the <em>model-agnostic</em> bits the interpreter needs directly: the
 * deferred-conflict flag, checked at a safe point (top of the bytecode loop) and at thread end, so a
 * conflict raised inside a synchronisation action surfaces as a catchable guest {@code Error} without
 * corrupting the sync machinery. Everything else (staging, base/head, merge) is model-private and
 * accessed by the owning {@link MemoryModel} through its concrete view type.
 */
public interface MemoryView {

    /** Whether a conflict has been recorded and is pending to be raised at the next safe point. */
    boolean hasPendingConflict();

    /** Take (and clear) the pending conflict message; called just before raising the guest error. */
    String takePendingConflict();
}
