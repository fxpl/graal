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
 * The epoch backing's per-monitor (and per-thread-object, for join) published synchronisation state —
 * the analogue of the DAG backing's {@link LockState}, but indexed by <em>location</em> instead of
 * pointing at a commit. It is the "lock branch" flattened into a table: for each location published
 * through this monitor, the latest writer's {@link EpochEntry} (epoch + value), plus the publisher's
 * {@link VectorClock} frontier (FastTrack's per-lock clock {@code L_m}).
 *
 * <p>Acquiring scans the table and imports the entries the acquirer has not seen (O(table), not
 * O(divergence)); releasing merges the holder's whole {@code seen} into it. Held in a side map keyed by
 * the monitor object and accessed only under that monitor, so — like {@link LockState} — it needs no
 * internal synchronisation. {@code clock} is {@code null} until the first publish.
 */
public final class EpochLockState {
    final Map<VersionedLocation, EpochEntry> table = new HashMap<>();
    VectorClock clock;
}
