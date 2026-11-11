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

import java.util.BitSet;

public class SeenSet {

    // one bit per sync state, by its position in the compression run, never changed once made
    private final BitSet seen;

    private SeenSet(BitSet seen) {
      this.seen = seen;
    }

    // the set with only the sync state at this position
    public static SeenSet of(int position) {
      BitSet seen = new BitSet();
      seen.set(position);
      return new SeenSet(seen);
    }

    public SeenSet union(SeenSet other) {
      if (other == this) {
        return this;
      }

      BitSet both = new BitSet();
      both.or(seen);
      both.or(other.seen);

      // when either set is already the union, return it, so commits down a chain share one set
      if (both.equals(seen)) {
        return this;
      }
      if (both.equals(other.seen)) {
        return other;
      }
      return new SeenSet(both);
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof SeenSet other && seen.equals(other.seen);
    }

    @Override
    public int hashCode() {
      return seen.hashCode();
    }

}