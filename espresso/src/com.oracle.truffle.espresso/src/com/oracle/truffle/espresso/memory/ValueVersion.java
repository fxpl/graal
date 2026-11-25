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

import java.lang.ref.WeakReference;

import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

// one version of a field's value that refers to an object or array, held weakly
// the object whose field it is pins the value, so the value lives while that object does
final class ValueVersion extends WeakReference<StaticObject> {

    private ValueVersion(StaticObject value) {
        super(value);
    }

    /** {@code value} as a version holds it, an object or array wrapped, a boxed primitive or guest null as it is. */
    static Object wrap(Object value) {
        if (value instanceof StaticObject object && StaticObject.notNull(object)) {
            return new ValueVersion(object);
        }
        return value;
    }

    /** The value {@code held} stands for, as {@link #wrap} took it. */
    static Object unwrap(Object held) {
        if (held instanceof ValueVersion version) {
            return version.value();
        }
        return held;
    }

    /** The reference {@code held} stands for, a reference array's element as {@link #wrap} took it. */
    static StaticObject unwrapReference(Object held) {
        if (held instanceof ValueVersion version) {
            return version.value();
        }
        // guest null, which wrap leaves as it is
        if (held instanceof StaticObject object) {
            return object;
        }
        throw EspressoError.shouldNotReachHere("a reference array's version holds a primitive");
    }

    private StaticObject value() {
        StaticObject value = get();
        // a reader reaches a version only through its live object, which pins the value
        assert value != null : "a version's value was collected while its object was alive, so it was never pinned";
        return value;
    }
}
