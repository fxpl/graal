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
import java.util.function.BiConsumer;

import com.oracle.truffle.espresso.impl.Field;

/** The writes to an object's fields, laid out as the object is, by each field's slot, with their (boxed) values. */
final class FieldChanges implements ObjectChanges {

    // the holder's field table, the class's own, which maps a slot back to its field
    private final Field[] table;
    // each slot's written value, null where it was not written (guest null is StaticObject.NULL)
    private final Object[] values;

    private FieldChanges(Field[] table, Object[] values) {
        this.table = table;
        this.values = values;
    }

    /** The written fields of {@code w}, with their values. */
    static FieldChanges of(WorkingObject w) {
        Object[] written = new Object[w.table.length];
        for (int slot = w.written.nextSetBit(0); slot >= 0; slot = w.written.nextSetBit(slot + 1)) {
            written[slot] = w.values[slot];
        }
        return new FieldChanges(w.table, written);
    }

    /** The writes in {@code values}, by slot of {@code table}, null where unwritten. Takes ownership of values. */
    static FieldChanges of(Field[] table, Object[] values) {
        return new FieldChanges(table, values);
    }

    /** No writes yet to an object laid out by {@code table}, for writes chosen one by one. */
    static FieldChanges empty(Field[] table) {
        return new FieldChanges(table, new Object[table.length]);
    }

    Field[] table() {
        return table;
    }

    /** The value written to {@code field}, or null if it was not written. */
    Object get(Field field) {
        return values[field.getSlot()];
    }

    /** Write {@code value} to {@code field}, unless it is already written. */
    void putIfAbsent(Field field, Object value) {
        if (values[field.getSlot()] == null) {
            values[field.getSlot()] = value;
        }
    }

    /** The field in {@code slot}. */
    Field field(int slot) {
        return table[slot];
    }

    /** The written slots, a new set the caller may change. */
    BitSet written() {
        BitSet written = new BitSet(values.length);
        for (int slot = 0; slot < values.length; slot++) {
            if (values[slot] != null) {
                written.set(slot);
            }
        }
        return written;
    }

    boolean isEmpty() {
        for (Object value : values) {
            if (value != null) {
                return false;
            }
        }
        return true;
    }

    /** Each written field with its value, in slot order. */
    void forEachWritten(BiConsumer<Field, Object> action) {
        for (int slot = 0; slot < values.length; slot++) {
            if (values[slot] != null) {
                action.accept(table[slot], values[slot]);
            }
        }
    }

    /** The writes to {@code slots} are dropped. */
    void remove(BitSet slots) {
        for (int slot = slots.nextSetBit(0); slot >= 0; slot = slots.nextSetBit(slot + 1)) {
            values[slot] = null;
        }
    }

    /** Write these values into {@code w}'s working values (its written marks are left alone). */
    void applyTo(WorkingObject w) {
        assert w.table.length == table.length : "changes laid out differently from the working object";
        for (int slot = 0; slot < values.length; slot++) {
            if (values[slot] != null) {
                w.values[slot] = values[slot];
            }
        }
    }

    @Override
    public ObjectChanges createFoldWithOlder(ObjectChanges older) {
        // the older writes, then these over them
        Object[] folded = ((FieldChanges) older).values.clone();
        overlay(values, folded);
        return new FieldChanges(table, folded);
    }

    @Override
    public void putAll(ObjectChanges newer) {
        overlay(((FieldChanges) newer).values, values);
    }

    @Override
    public long writeCount() {
        long count = 0;
        for (Object value : values) {
            if (value != null) {
                count++;
            }
        }
        return count;
    }

    // the written slots of from copied into to
    private static void overlay(Object[] from, Object[] to) {
        for (int slot = 0; slot < from.length; slot++) {
            if (from[slot] != null) {
                to[slot] = from[slot];
            }
        }
    }
}
