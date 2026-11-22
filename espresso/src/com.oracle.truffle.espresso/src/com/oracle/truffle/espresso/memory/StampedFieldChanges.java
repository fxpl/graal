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

import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.meta.EspressoError;

/** An object's latest field writes in a {@link Snapshot}, each stamped with the epoch of the commit that made it. */
final class StampedFieldChanges implements StampedChanges {

    private final Map<Field, Write> writes;

    private StampedFieldChanges(Map<Field, Write> writes) {
        this.writes = writes;
    }

    /** The values of {@code changes}, each stamped with {@code epoch}, the epoch of the commit making them. */
    static StampedFieldChanges of(FieldChanges changes, Epoch epoch) {
        Map<Field, Write> stamped = HashMap.newHashMap(changes.values().size());
        changes.values().forEach((field, value) -> stamped.put(field, new Write(value, epoch)));
        return new StampedFieldChanges(Map.copyOf(stamped));
    }

    /** Writes already chosen, each keeping its own epoch, as when a merge takes some from each side. */
    static StampedFieldChanges of(Map<Field, Write> writes) {
        return new StampedFieldChanges(Map.copyOf(writes));
    }

    /** The latest write to {@code field}, or null if there is none. */
    Write get(Field field) {
        return writes.get(field);
    }

    /** Every field's latest write, which cannot be modified. */
    Map<Field, Write> writes() {
        return writes;
    }

    @Override
    public StampedChanges createFoldWithOlder(StampedChanges older) {
        if (!(older instanceof StampedFieldChanges o)) {
            throw EspressoError.shouldNotReachHere("an object's changes are always of one kind");
        }
        Map<Field, Write> folded = new HashMap<>(o.writes);
        folded.putAll(writes);
        return new StampedFieldChanges(Map.copyOf(folded));
    }
}
