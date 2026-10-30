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
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * Whether the Sync-as-Share model owns a location. Every barrier asks here, and every question is
 * answered whole: first whether a versioned model is running ({@link #modelOn}), then the location.
 * Callers add no checks of their own.
 *
 * <p>An object is versioned once {@link StaticObject#isVersioned()}; only objects
 * {@link StaticObject#isEligibleForVersioning() eligible for versioning} (today: allocated by
 * a method of a class that is not boot loaded) ever become versioned. Statics have no instance, so
 * they key on whether their class is boot loaded. See {@code design/versioning-provenance.md}.
 */
public final class Versioning {

    private Versioning() {
    }

    // A versioned model is selected (a compilation constant, so under shared every check here folds
    // away) and active (not during VM bootstrap). Reached through a class the caller already has: a
    // language or context lookup by thread is a thread-local read, too slow for every access.
    private static boolean modelOn(Klass klass) {
        return klass.getLanguage().isVersionedMemorySelected() && klass.getContext().getMemoryModel().isActive();
    }

    /**
     * Is {@code klass} defined by the boot class loader? Not the same as "a JDK class": the platform
     * loader also defines JDK classes, and those count as not boot loaded.
     */
    private static boolean isBootLoadedClass(Klass klass) {
        return StaticObject.isNull(klass.getDefiningClassLoader());
    }

    /** Does the model own {@code obj}'s instance fields, or, for an array, its elements? */
    public static boolean versionsObject(StaticObject obj) {
        return modelOn(obj.getKlass()) && obj.isVersioned();
    }

    /**
     * Does the model own {@code field} of {@code obj}? A static if its class is not boot loaded, an
     * instance field if {@code obj} is versioned. Volatile fields are owned too: the
     * model gives them their synchronisation, and keeps their value in the raw slot.
     */
    public static boolean versionsField(StaticObject obj, Field field) {
        // Hidden fields are VM-injected storage (not guest-declared) holding host-side objects — the
        // EspressoLock in Thread.0toNativeLock, the host Thread in 0hostThread — which are never guest
        // values and never app data. They must stay raw: the hidden reader (getHiddenObjectHelper) is
        // already un-routed, so versioning the write alone would stage a value the reader never sees.
        if (!modelOn(field.getDeclaringKlass()) || field.isHidden()) {
            return false;
        }
        return field.isStatic() ? !isBootLoadedClass(field.getDeclaringKlass()) : obj.isVersioned();
    }

    /** Mark {@code obj} eligible for versioning if it should be: today, if application code allocated it. */
    public static StaticObject markIfEligible(StaticObject obj, Method allocator) {
        if (modelOn(allocator.getDeclaringKlass()) && !isBootLoadedClass(allocator.getDeclaringKlass())) {
            obj.markEligibleForVersioning();
        }
        return obj;
    }

    /** Walk the object graph and mark everything as needing versioning */
    private static void markForVersioning(StaticObject root) {
        Deque<StaticObject> roots = new ArrayDeque<StaticObject>();
        roots.push(root);

        while (!roots.isEmpty()) {
            StaticObject o = roots.pop();
            if (StaticObject.isNull(o) || !o.isEligibleForVersioning() || o.isVersioned()) {
                continue;
            }
            o.markVersioned();

            Klass klass = o.getKlass();
            if (klass.isArray()) {
                // walk the reference elements of the array and push them as new roots to explore
                if (!((ArrayKlass) klass).getComponentType().isPrimitive()) {
                    for (StaticObject element : o.<StaticObject[]> getRawArray(klass.getLanguage())) {
                        roots.push(element);
                    }
                }
            } else {
                // walk the eligible reference fields of the object and push them as new roots to explore
                for (Field f : ((ObjectKlass) klass).getFieldTable()) {
                    if (!f.getKind().isPrimitive() && !f.isHidden() && !f.isRemoved()) {
                        roots.push((StaticObject) f.getRawValue(o));
                    }
                }
            }

        }
    }

    public static void versionReachableFrom(Object target) {
        if (!(target instanceof StaticObject o) || StaticObject.isNull(o) || !modelOn(o.getKlass()) || !o.isEligibleForVersioning() || o.isVersioned()) {
            return;
        }
        markForVersioning(o);
    }

    /** An event, check whether the value requires versioning, if the target is versioned and the value is not yet
     * versioned, but is eligible, then mark the value for versioning
     */
    public static void versionValueIfTargetShared(StaticObject target, Object value) {
        if (!modelOn(target.getKlass()) || !(value instanceof StaticObject o) || StaticObject.isNull(o) || !o.isEligibleForVersioning() || o.isVersioned()) {
            return;
        }
        if (target != null && target.isEligibleForVersioning() && !target.isVersioned()) {
            return;
        }
        markForVersioning(o);
    }

    /** As versionValueIfTargetShared, for every reference in values[from, to). */
    public static void versionValuesIfTargetShared(StaticObject target, StaticObject[] values, int from, int to) {
        if (!modelOn(target.getKlass())) {
            return;
        }
        if (target != null && target.isEligibleForVersioning() && !target.isVersioned()) {
            return;
        }
        for (int i = from; i < to; i++) {
            markForVersioning(values[i]);            // returns at once for null, versioned or ineligible values
        }
    }


}
