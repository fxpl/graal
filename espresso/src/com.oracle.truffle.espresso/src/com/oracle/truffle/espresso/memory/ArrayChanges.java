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

import java.lang.reflect.Array;
import java.util.BitSet;

final class ArrayChanges {
  Object changes;
  BitSet written;

  private ArrayChanges(Object changes, BitSet written) {
      this.changes = changes;
      this.written = written;
  }

  private static void copyWritten(BitSet written, Object src, Object dst) {
      // Copy the set ranges of the array to the local storage,
      // we don't want to flat copy the array as this may copy StaticObjects
      // that we don't have a way to easily find and delete
      int to = 0;
      for (int from = written.nextSetBit(to); from >= 0; from = written.nextSetBit(to)) {
          to = written.nextClearBit(from);
          System.arraycopy(src, from, dst, from, to - from);
      }
  }

  static ArrayChanges of(WorkingArray w) {
      Object values = Array.newInstance(w.storage.getClass().getComponentType(), Array.getLength(w.storage));
      BitSet written = (BitSet) w.written.clone();
      copyWritten(written, w.storage, values);
      return new ArrayChanges(values, written);
  }

  BitSet written() {
      return written;
  }

  void applyTo(Object storage) {
      copyWritten(written, changes, storage);
  }

  void putAll(ArrayChanges newer) {
      copyWritten(newer.written, newer.changes, changes);
      written.or(newer.written);
  }

  // A new ArrayChanges holding the writes of older and of these, these winning where both wrote.
  // Neither is modified, as other threads may be reading them.
  ArrayChanges createFoldWithOlder(ArrayChanges older) {
      Object values = Array.newInstance(changes.getClass().getComponentType(), Array.getLength(changes));
      copyWritten(older.written, older.changes, values);
      copyWritten(written, changes, values);
      BitSet all = (BitSet) written.clone();
      all.or(older.written);
      return new ArrayChanges(values, all);
  }

}