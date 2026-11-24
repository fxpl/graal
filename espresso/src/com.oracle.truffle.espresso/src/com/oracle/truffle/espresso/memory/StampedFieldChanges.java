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

import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.meta.EspressoError;

/**
 * An object's latest field writes in a {@link Snapshot}, laid out as the object is, by each field's slot,
 * each stamped with the epoch of the commit that made it.
 */
final class StampedFieldChanges implements StampedChanges {

    // the holder's field table, the class's own, which maps a slot back to its field
    private final Field[] table;
    // each slot's latest value, null where it was never written
    private final Object[] values;
    // the epoch of each slot's latest write, null where it was never written
    private final Epoch[] epochs;

    private StampedFieldChanges(Field[] table, Object[] values, Epoch[] epochs) {
        this.table = table;
        this.values = values;
        this.epochs = epochs;
    }

    /** The values of {@code changes}, each stamped with {@code epoch}, the epoch of the commit making them. */
    static StampedFieldChanges of(FieldChanges changes, Epoch epoch) {
        Field[] table = changes.table();
        Object[] values = new Object[table.length];
        Epoch[] epochs = new Epoch[table.length];
        changes.forEachWritten((field, value) -> {
            values[field.getSlot()] = value;
            epochs[field.getSlot()] = epoch;
        });
        return new StampedFieldChanges(table, values, epochs);
    }

    /** Writes already chosen, each keeping its own epoch, as when a merge takes some from each side. Takes ownership of both arrays. */
    static StampedFieldChanges of(Field[] table, Object[] values, Epoch[] epochs) {
        return new StampedFieldChanges(table, values, epochs);
    }

    Field[] table() {
        return table;
    }

    /** The latest value written to {@code field}, or null if it was never written. */
    Object value(Field field) {
        return values[field.getSlot()];
    }

    /** Each slot's latest value, which must not be modified. */
    Object[] values() {
        return values;
    }

    /** Each slot's latest write epoch, null where unwritten, which must not be modified. */
    Epoch[] epochs() {
        return epochs;
    }

    /** The number of fields written. */
    int writeCount() {
        int count = 0;
        for (Epoch epoch : epochs) {
            if (epoch != null) {
                count++;
            }
        }
        return count;
    }

    @Override
    public StampedChanges updateWith(StampedChanges newer) {
        if (!(newer instanceof StampedFieldChanges n)) {
            throw EspressoError.shouldNotReachHere("an object's changes are always of one kind");
        }
        // these writes, with the slots newer wrote replaced
        Object[] updatedValues = values.clone();
        Epoch[] updatedEpochs = epochs.clone();
        for (int slot = 0; slot < n.epochs.length; slot++) {
            if (n.epochs[slot] != null) {
                updatedValues[slot] = n.values[slot];
                updatedEpochs[slot] = n.epochs[slot];
            }
        }
        return new StampedFieldChanges(table, updatedValues, updatedEpochs);
    }
}
