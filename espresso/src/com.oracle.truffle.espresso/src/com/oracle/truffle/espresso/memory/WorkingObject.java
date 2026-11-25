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

import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * A view's copy of the fields of a versioned object it has accessed, laid out as the object is, by each
 * field's slot: the value it sees for each field it has read or written, and the fields written since the
 * view last committed.
 */
final class WorkingObject {

    // the holder's field table, the class's own, which maps a slot back to its field
    final Field[] table;
    // the value the view sees in each slot, null until read or written (guest null is StaticObject.NULL)
    final Object[] values;
    // the slots written since the view last committed
    final BitSet written = new BitSet();
    // the object this is a copy of, held weakly, queued when it dies so this copy is dropped
    final WorkspaceReference object;

    WorkingObject(Field[] table, WorkspaceReference object) {
        this.table = table;
        this.values = new Object[table.length];
        this.object = object;
    }

    // the field table holder's fields index, its class's statics for a static field and its instance fields otherwise
    static Field[] tableOf(StaticObject holder, Field field) {
        assert field.getSlot() >= 0 : "an extension field reached the model";
        if (field.isStatic()) {
            return field.getDeclaringKlass().getStaticFieldTable();
        }
        if (holder.getKlass() instanceof ObjectKlass k) {
            return k.getFieldTable();
        }
        throw EspressoError.shouldNotReachHere("a field of an object with no instance fields");
    }
}
