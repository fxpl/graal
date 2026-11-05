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

import java.util.ArrayDeque;
import java.util.Deque;

import com.oracle.truffle.espresso.impl.ArrayKlass;
import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * Whether the Sync-as-Share model owns a location, from two properties: an object is
 * {@linkplain StaticObject#isShared() shared} once more than one thread may reach it, and a field is
 * {@linkplain Field#isUnversionable() unversionable} when it is VM state (every hidden field, and the
 * guest fields marked {@link Unversioned}). The model versions a shared object's fields that are not
 * VM state, and counts synchronisation on shared objects; everything else is the ordinary heap.
 *
 * <p>An object becomes shared when it is stored somewhere shared (a class's statics are shared from
 * creation), and when a thread starts (the {@code Thread} object). Sharing spreads to everything reachable
 * through fields that are not VM state. Every question is answered whole: callers add no checks of their
 * own.
 */
public final class Versioning {

    private Versioning() {
    }

    // Is a versioned model selected (a compilation constant, so under shared every check here folds away)
    // and active (not during VM bootstrap)? Reached through a class the caller already has: a language or
    // context lookup by thread is a thread-local read, too slow for every access.
    private static boolean isModelActive(Klass klass) {
        return klass.getLanguage().isVersionedMemorySelected() && klass.getContext().getMemoryModel().isActive();
    }

    /**
     * Does the model own {@code obj}: its instance fields, or for an array its elements, and
     * synchronisation on it? Exactly when it is shared.
     */
    public static boolean isShared(StaticObject obj) {
        return isModelActive(obj.getKlass()) && obj.isShared();
    }

    /**
     * Does the model own {@code field} of {@code obj} (for a static, {@code obj} is the class's statics)?
     * When {@code obj} is shared and the field is not VM state. Volatile fields are owned too: the model
     * gives them their synchronisation, and keeps their value in the raw slot.
     */
    public static boolean versionsField(StaticObject obj, Field field) {
        return !field.isUnversionable() && isShared(obj);
    }

    /** Mark {@code root} shared, and everything reachable from it through fields that are not VM state. */
    public static void markShared(StaticObject root) {
        if (StaticObject.isNull(root) || !isModelActive(root.getKlass())) {
            return;
        }
        Deque<StaticObject> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            StaticObject o = pending.pop();
            if (StaticObject.isNull(o) || o.isShared()) {
                continue;
            }
            o.setShared();
            VersioningStats.onShared(o.isArray());

            Klass klass = o.getKlass();
            if (klass.isArray()) {
                if (!((ArrayKlass) klass).getComponentType().isPrimitive()) {
                    for (StaticObject element : o.<StaticObject[]> getRawArray(klass.getLanguage())) {
                        pending.push(element);
                    }
                }
            } else {
                for (Field f : ((ObjectKlass) klass).getFieldTable()) {
                    if (!f.getKind().isPrimitive() && !f.isUnversionable() && !f.isRemoved()) {
                        pending.push((StaticObject) f.getRawValue(o));
                    }
                }
            }
        }
    }

    /**
     * {@code value} was stored into {@code target} (a field that is not VM state, or an element): if
     * {@code target} is shared, so now is {@code value}. {@code value} is as the accessor received it;
     * only a guest object can become shared.
     */
    public static void onStore(StaticObject target, Object value) {
        if (value instanceof StaticObject o && isShared(target)) {
            markShared(o);
        }
    }

    /** As {@link #onStore}, for every reference stored into {@code target}: {@code values[from, to)}. */
    public static void onStoreRange(StaticObject target, StaticObject[] values, int from, int to) {
        if (!isShared(target)) {
            return;
        }
        for (int i = from; i < to; i++) {
            markShared(values[i]);            // returns at once for null or already shared values
        }
    }
}
