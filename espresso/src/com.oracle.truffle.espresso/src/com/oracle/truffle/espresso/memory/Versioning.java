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
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * The single authority for "does the Sync-as-Share model version this location?" — the discriminator
 * every gate consults (bytecode getfield/putfield/aaload/aastore, {@code Unsafe}, reflection), so the
 * decision is defined once instead of re-derived at each site (where it previously drifted: fields
 * keyed on the field's declaring class, arrays on the executing frame, {@code Unsafe} hand-copied the
 * field test).
 *
 * <p>The criterion is instance <b>provenance</b>: an object/array is versioned iff it is application
 * data — {@link StaticObject#isAppOwned()}, set at allocation and propagated on stores — not by the
 * declaring class. Statics have no instance, so they key on the class's loader (its owner). See
 * {@code design/versioning-provenance.md}.
 */
public final class Versioning {

    private Versioning() {
    }

    /** The atom: is {@code klass} defined by a non-boot (application) class loader? */
    private static boolean isApplicationClass(Klass klass) {
        return !StaticObject.isNull(klass.getDefiningClassLoader());
    }

    /** Is an object allocated by {@code allocator} application data, i.e. should it be marked app-owned? */
    public static boolean allocatesAppOwned(Method allocator) {
        return isApplicationClass(allocator.getDeclaringKlass());
    }

    /**
     * An instance field of {@code receiver} is versioned iff the object is application data — decided by
     * its provenance ({@link StaticObject#isAppOwned()}), set at allocation and propagated on stores, NOT
     * by the field's declaring class. So an app-created instance of a library class (e.g. a {@code
     * HashMap} the program {@code new}'d) is versioned, while the runtime's own instances are not.
     */
    public static boolean versionsInstance(StaticObject receiver) {
        return receiver.isAppOwned();
    }

    /** An array element is versioned iff the array is application data (its own provenance). */
    public static boolean versionsArray(StaticObject array) {
        return array.isAppOwned();
    }

    /**
     * A static field is versioned iff its declaring class is application-loaded. A static's owner is the
     * class (there is no instance), and a class's layer is fixed by who loaded it — so the loader is the
     * right ownership test here (unlike for instances, where the app can create instances of runtime
     * classes). See {@code design/versioning-provenance.md} §4.2.
     */
    public static boolean versionsStatic(Field field) {
        return isApplicationClass(field.getDeclaringKlass());
    }

    /** Is instance {@code field} of {@code obj} a versioned location (used by {@code Field}'s accessors)? */
    public static boolean versionsInstanceField(StaticObject obj, Field field) {
        // Hidden fields are VM-injected storage (not guest-declared) holding host-side objects — the
        // EspressoLock in Thread.0toNativeLock, the host Thread in 0hostThread — which are never guest
        // values and never app data. They must stay raw: the hidden reader (getHiddenObjectHelper) is
        // already un-routed, so versioning the write alone would stage a value the reader never sees.
        if (!versionsInstance(obj) || field.isVolatile() || field.isHidden()) {
            return false;
        }
        var ctx = field.getDeclaringKlass().getContext();
        return ctx.isVersionedMemoryEnabled();
    }
}
