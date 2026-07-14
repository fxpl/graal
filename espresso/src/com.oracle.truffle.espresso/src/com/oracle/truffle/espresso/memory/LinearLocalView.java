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

/**
 * A thread's private view of memory (prototype: {@code LocalVersionStore}). Holds the staging area
 * {@code δ} (uncommitted writes) and the base counter — the position in the global history this
 * thread has observed. Owned by a single guest thread (lives on its {@code EspressoThreadLocalState}),
 * so it needs no synchronisation of its own.
 */
public final class LinearLocalView {

    private long base;
    private final Map<LinearLocation, Object> staging = new HashMap<>();
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
    public Object getStaged(LinearLocation loc) {
        return staging.get(loc);
    }

    public void stage(LinearLocation loc, Object value) {
        staging.put(loc, value);
    }

    public Map<LinearLocation, Object> staging() {
        return staging;
    }

    public boolean stagingEmpty() {
        return staging.isEmpty();
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
