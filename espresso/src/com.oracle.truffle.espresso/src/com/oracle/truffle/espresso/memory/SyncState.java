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
 * The state for one synchronisation object (a lock, a volatile field, a class's initialisation, a
 * thread, or linear's global history): the version released to it, {@code null} until the first push,
 * and the clock of the thread that released it. What a version is, and how a state takes and lets go of
 * one, belongs to the subclass the model's representation makes ({@link CommitSyncState},
 * {@link SnapshotSyncState}). Accessed under the lock for locks, and under {@code synchronized} on this
 * object otherwise.
 */
public abstract class SyncState<V> {

    public enum Kind {
        THREAD,
        CLASS_INIT,
        LOCK,
        VOLATILE,
        GLOBAL
    }

    // the clock of the thread that last set the version, so a pull can tell it already has it
    Clock clock;
    final Kind kind;

    SyncState(Kind kind) {
        this.kind = kind;
    }

    /** The version this state holds, or null if nothing has been released to it. */
    abstract V get();

    /** Hold {@code next} (or nothing) in place of the version held now. */
    abstract void set(V next);
}
