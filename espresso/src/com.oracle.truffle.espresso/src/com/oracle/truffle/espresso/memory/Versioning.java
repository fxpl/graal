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
 * Whether the Sync-as-Share model owns a location, from three properties.
 * <ul>
 * <li>An object is {@linkplain StaticObject#isShared() shared} when another thread can reach it: it is
 * reachable from a root (a class's statics, a started thread). Synchronisation on a shared object is the
 * program's synchronisation.</li>
 * <li>An object is {@linkplain StaticObject#isVersioned() versioned} when its data is the program's: it
 * became reachable through the program's state (an application class's statics, a started thread's task,
 * or an object already versioned). The model versions its fields.</li>
 * <li>A field is {@linkplain Field#isUnversionable() unversionable} when the VM writes it behind the
 * program's back (every hidden field, and the fields marked {@link Unversioned}): it is never versioned,
 * and it does not make what it refers to shared or versioned.</li>
 * </ul>
 * Every object that exists when the model is turned on was built during VM startup and hangs off a root,
 * so it starts shared; it is the JDK's, so it is not versioned. Sharing and versioning then spread along
 * stores, through fields that are not unversionable. Every question is answered whole: callers add no
 * checks of their own.
 */
public final class Versioning {

    private Versioning() {
    }

    /**
     * Is a versioned model selected (a compilation constant, so under shared every check here folds away)
     * and active (not during VM bootstrap)? Reached through a class the caller already has: a language or
     * context lookup by thread is a thread-local read, too slow for every access.
     */
    public static boolean isModelActive(Klass klass) {
        return klass.getLanguage().isVersionedMemorySelected() && klass.getContext().getMemoryModel().isActive();
    }

    /**
     * Is {@code klass} part of the runtime's implementation, in a {@code jdk.internal} or {@code sun}
     * package? Programs cannot keep their data there, and its objects link unrelated users together (a
     * cleaner's list of every registered resource), so the model owns none of its fields. Asked by
     * package alone, as fields are built before {@link #isJdkClass} can ask a loader.
     */
    public static boolean isRuntimeInternal(Klass klass) {
        String pkg = klass.getRuntimePackage().toString();
        return pkg.equals("jdk/internal") || pkg.startsWith("jdk/internal/") || pkg.equals("sun") || pkg.startsWith("sun/");
    }

    /** Is {@code klass} part of the JDK: defined by the boot or the platform class loader? */
    public static boolean isJdkClass(Klass klass) {
        StaticObject loader = klass.getDefiningClassLoader();
        if (StaticObject.isNull(loader)) {
            return true;
        }
        ObjectKlass platform = klass.getMeta().jdk_internal_loader_ClassLoaders$PlatformClassLoader;
        return platform != null && loader.getKlass() == platform;
    }

    /** Is synchronisation on {@code obj} the program's? When another thread can reach it. */
    public static boolean isShared(StaticObject obj) {
        return isModelActive(obj.getKlass()) && obj.isShared();
    }

    /** Does the model own {@code obj}'s data: its instance fields, or for an array its elements? */
    public static boolean isVersioned(StaticObject obj) {
        return isModelActive(obj.getKlass()) && obj.isVersioned();
    }

    /**
     * Does the model own {@code field} of {@code obj} (for a static, {@code obj} is the class's statics)?
     * When {@code obj} is versioned and the field is not VM or JDK state. Volatile fields are owned too:
     * the model gives them their synchronisation, and keeps their value in the raw slot.
     */
    public static boolean versionsField(StaticObject obj, Field field) {
        return !field.isUnversionable() && isVersioned(obj);
    }

    /** Mark {@code root} shared, and everything reachable from it through fields that are not unversionable. */
    public static void markShared(StaticObject root) {
        mark(root, false);
    }

    /** Mark {@code root} versioned (and so shared), and everything reachable from it likewise. */
    public static void markVersioned(StaticObject root) {
        mark(root, true);
    }

    // The walk behind both: a versioning walk continues through objects that are shared but not yet
    // versioned, a sharing walk stops at any shared object
    private static void mark(StaticObject root, boolean versioned) {
        if (StaticObject.isNull(root) || !isModelActive(root.getKlass())) {
            return;
        }
        Deque<StaticObject> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            StaticObject o = pending.pop();
            if (StaticObject.isNull(o) || (versioned ? o.isVersioned() : o.isShared())) {
                continue;
            }
            if (!o.isShared()) {
                o.setShared();
                VersioningStats.onShared(o.isArray());
            }
            if (versioned) {
                o.setVersioned();
                VersioningStats.onVersioned(o.isArray());
            }

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
     * {@code value} was stored into {@code target} (a field that is not unversionable, or an element): if
     * {@code target} is versioned, so now is {@code value}; if it is only shared, {@code value} is shared.
     * {@code value} is as the accessor received it; only a guest object is marked.
     */
    public static void onStore(StaticObject target, Object value) {
        if (value instanceof StaticObject o && isShared(target)) {
            mark(o, target.isVersioned());
        }
    }

    /** As {@link #onStore}, for every reference stored into {@code target}: {@code values[from, to)}. */
    public static void onStoreRange(StaticObject target, StaticObject[] values, int from, int to) {
        if (!isShared(target)) {
            return;
        }
        boolean versioned = target.isVersioned();
        for (int i = from; i < to; i++) {
            mark(values[i], versioned);            // returns at once for null or already marked values
        }
    }

    /** The parent is starting {@code thread}: the child can now reach it, and what the program hands it. */
    public static void onSpawn(StaticObject thread) {
        Klass klass = thread.getKlass();
        if (!isModelActive(klass)) {
            return;
        }
        markShared(thread);
        // a program's Thread subclass: its own fields are program data
        if (!isJdkClass(klass)) {
            thread.setVersioned();
            VersioningStats.onVersioned(false);
            for (Field f : ((ObjectKlass) klass).getFieldTable()) {
                // only fields the program declares, not the JDK's (holder, group, ...)
                if (!f.getKind().isPrimitive() && !f.isUnversionable() && !f.isRemoved() && !isJdkClass(f.getDeclaringKlass())) {
                    markVersioned((StaticObject) f.getRawValue(thread));
                }
            }
        }
        // the Runnable the program hands the child
        markVersioned(task(thread));
    }

    // the thread's Runnable: FieldHolder.task (JDK 19+), Thread.target before
    private static StaticObject task(StaticObject thread) {
        StaticObject holder = rawReference(thread, "holder");
        return StaticObject.isNull(holder) ? rawReference(thread, "target") : rawReference(holder, "task");
    }

    // obj's reference field by name, read raw; Meta has no handle for these
    private static StaticObject rawReference(StaticObject obj, String name) {
        for (Field f : ((ObjectKlass) obj.getKlass()).getFieldTable()) {
            if (!f.getKind().isPrimitive() && f.getName().toString().equals(name)) {
                return (StaticObject) f.getRawValue(obj);
            }
        }
        return StaticObject.NULL;
    }
}
