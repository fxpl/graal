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
import java.util.Set;

import com.oracle.truffle.espresso.impl.Field;

/** The writes to an object's fields: each field written, with its (boxed) value. */
final class FieldChanges implements ObjectChanges {

    private final Map<Field, Object> values;

    private FieldChanges(Map<Field, Object> values) {
        this.values = values;
    }

    /** The written fields of {@code w}, with their values. */
    static FieldChanges of(WorkingObject w) {
        Map<Field, Object> written = HashMap.newHashMap(w.written.size());
        for (Field field : w.written) {
            written.put(field, w.values.get(field));
        }
        return new FieldChanges(written);
    }

    /** The value written to {@code field}, or null if it was not written. */
    Object get(Field field) {
        return values.get(field);
    }

    Set<Field> fields() {
        return values.keySet();
    }

    Map<Field, Object> values() {
        return values;
    }

    @Override
    public ObjectChanges createFoldWithOlder(ObjectChanges older) {
        Map<Field, Object> folded = new HashMap<>(((FieldChanges) older).values);
        folded.putAll(values);
        return new FieldChanges(folded);
    }

    @Override
    public void putAll(ObjectChanges newer) {
        values.putAll(((FieldChanges) newer).values);
    }

    @Override
    public long writeCount() {
        return values.size();
    }
}
