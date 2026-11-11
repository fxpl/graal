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
 * You have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.espresso.memory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SeenSet {

    private static final Map<Set<SyncState>, SeenSet> table = new ConcurrentHashMap<>();

    public static final SeenSet EMPTY = new SeenSet(Set.of());

    private final Set<SyncState> seen;

    // remembers what with returned for each state, so repeats skip the copy and the table lookup
    private final Map<SyncState, SeenSet> next = new ConcurrentHashMap<>();

    private SeenSet(Set<SyncState> seen) {
      this.seen = seen;
    }

    // return an interned version of the set
    public SeenSet with(SyncState state) {
      SeenSet memo = next.get(state);
      if (memo != null) {
        return memo;
      }

      Set<SyncState> newSeen = new HashSet<>(seen);
      newSeen.add(state);

      final Set<SyncState> immSet = Set.copyOf(newSeen);
      SeenSet interned = table.computeIfAbsent(immSet, k -> new SeenSet(k));
      // racing threads all get the same interned set, so a plain put is fine
      next.put(state, interned);
      return interned;
    }

    // return an interned version of the union of both sets
    public SeenSet union(SeenSet other) {
      if (other == this || seen.containsAll(other.seen)) {
        return this;
      }
      if (other.seen.containsAll(seen)) {
        return other;
      }

      Set<SyncState> newSeen = new HashSet<>(seen);
      newSeen.addAll(other.seen);
      return table.computeIfAbsent(Set.copyOf(newSeen), k -> new SeenSet(k));
    }

    public boolean contains(SyncState state) {
      return seen.contains(state);
    }

    // forget every interned set, so the table does not keep dead states alive between compressions
    static void reset() {
      table.clear();
      EMPTY.next.clear();
    }

}