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
 * every gate consults (bytecode getfield/putfield/aaload/aastore, {@code Unsafe}, {@code arraycopy},
 * reflection, and the {@code MemoryModelSync} synchronization classifier), so the decision is defined
 * once instead of re-derived at each site (where it previously drifted: fields keyed on the field's
 * declaring class, arrays on the executing frame, {@code Unsafe} hand-copied the field test).
 *
 * <p><b>The criterion here is the current, provisional one</b> — "declared by a non-boot class loader" —
 * lifted <em>verbatim</em> from the former call sites so this is a behaviour-preserving centralisation.
 * It is known to be wrong (arrays contradict themselves across app/boot frames; application data inside
 * an app-created library instance escapes) and will be replaced by instance <b>provenance</b> — the
 * principled criterion is specified in {@code design/versioning-provenance.md}. Because every gate now
 * routes through here, that replacement is a single-file change.
 *
 * <p>The {@code isMemoryModelEnabled() && isActive()} guards stay at the call sites (they are the
 * partial-evaluation constants that fold the whole hook away under {@code --java.MemoryModel=shared});
 * this class is only the criterion.
 */
public final class Versioning {

    private Versioning() {
    }

    /** The atom: is {@code klass} defined by a non-boot (application) class loader? */
    public static boolean isApplicationClass(Klass klass) {
        return !StaticObject.isNull(klass.getDefiningClassLoader());
    }

    /** Field access is versioned iff the field's declaring class is application-loaded. */
    public static boolean versionsField(Field field) {
        return isApplicationClass(field.getDeclaringKlass());
    }

    /**
     * Array access is versioned iff the currently executing method is application-loaded.
     *
     * <p><b>Provisional and known-wrong:</b> keyed on the <em>accessing frame</em>, not the array, so the
     * same array is versioned from an application frame and not from a boot frame — the single-thread
     * self-contradiction the provenance redesign replaces (see {@code design/versioning-provenance.md}
     * §1, §4.1). Preserved as-is here only so the centralisation changes no behaviour.
     */
    public static boolean versionsArrayAccess(Method currentMethod) {
        return isApplicationClass(currentMethod.getDeclaringKlass());
    }
}
