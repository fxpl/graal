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
import java.util.Map;
import java.util.stream.Stream;

import com.oracle.truffle.espresso.impl.Field;

/**
 * The locations written on both sides of a merge: for each object the fields, and for each array the
 * indices, both sides wrote.
 */
record ConflictingLocations(Map<ObjectNumber, FieldSlots> fields, Map<ObjectNumber, BitSet> arrays) {

    static final ConflictingLocations NONE = new ConflictingLocations(Map.of(), Map.of());

    /** An object's fields by slot, with its field table to name them. */
    record FieldSlots(Field[] table, BitSet slots) {
        // mark the field in slot of object number, starting the object's slots if it has none
        static void add(Map<ObjectNumber, FieldSlots> conflicts, ObjectNumber number, Field[] table, int slot) {
            conflicts.computeIfAbsent(number, n -> new FieldSlots(table, new BitSet())).slots().set(slot);
        }

        // the fields named, for the report
        Stream<Field> fields() {
            return slots.stream().mapToObj(slot -> table[slot]);
        }
    }

    boolean isEmpty() {
        return fields.isEmpty() && arrays.isEmpty();
    }
}
