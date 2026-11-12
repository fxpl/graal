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

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The state for one synchronisation object (a lock, a volatile field, a class's initialisation, a
 * thread, or linear's global history): a commit, {@code null} until the first push. In branching a pull
 * merges this commit into the acting thread and a push replaces it with the thread's head; a thread's
 * own state holds its head. In linear every pull and push goes through the single global state, whose
 * commit is the head of the chain, and a thread's own state holds the commit it is on. The commit is
 * reference counted on the state's behalf, through {@link #set}. Accessed under the lock for locks, and
 * under {@code synchronized} on this object otherwise.
 */
public final class SyncState {

    public enum Kind {
        THREAD,
        CLASS_INIT,
        LOCK,
        VOLATILE,
        GLOBAL
    }

    Commit commit;
    // the clock of the thread that last set commit, so a pull can tell it already has it without walking
    Clock clock;
    final Kind kind;

    // every live state, so compression can find the commits they hold, held weakly so dead states drop out
    static final Set<SyncState> ALL = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    public SyncState(Kind kind) {
        this.kind = kind;
        ALL.add(this);
        VersioningStats.onSyncStateCreated(this);
    }

    /**
     * Point this state at {@code c} (or at nothing), taking this state's reference to it and releasing
     * the one it held. The new reference is taken before the old one is released, so moving to a
     * commit that the old one keeps alive never drops it to zero; setting the commit it already holds
     * does nothing.
     */
    void set(Commit c) {
        Commit old = commit;
        if (c == old) {
            return;
        }
        if (c != null) {
            c.incRef();
        }
        // under the graph's read side, so compression never sees a state halfway through moving
        Commit.GRAPH.readLock().lock();
        try {
            commit = c;
        } finally {
            Commit.GRAPH.readLock().unlock();
        }
        if (old != null) {
            old.decRef();
        }
    }
}
