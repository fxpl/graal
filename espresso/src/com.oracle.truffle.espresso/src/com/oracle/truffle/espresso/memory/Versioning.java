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
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * The single authority for "does the Sync-as-Share model version this location?" — the discriminator
 * every gate consults (bytecode getfield/putfield/aaload/aastore, {@code Unsafe}, reflection, and the
 * {@code MemoryModelSync} synchronization classifier), so the decision is defined once instead of
 * re-derived at each site (where it previously drifted: fields keyed on the field's declaring class,
 * arrays on the executing frame, {@code Unsafe} hand-copied the field test).
 *
 * <p>The criterion is instance <b>provenance</b>: an object/array is versioned iff it is application
 * data — {@link StaticObject#isAppOwned()}, set at allocation and propagated on stores — not by the
 * declaring class. Statics have no instance, so they key on the class's loader (its owner). See
 * {@code design/versioning-provenance.md}.
 *
 * <p>{@link #readInstance} additionally encapsulates the <em>read</em> of a versioned instance field
 * through the acting thread's view, so every accessor (bytecode and VM) routes through one place rather
 * than re-inlining the model dance. {@code isAppOwned()} is checked first and is always false under
 * {@code --java.MemoryModel=shared}, so the whole thing folds away there.
 */
public final class Versioning {

    private Versioning() {
    }

    /** The atom: is {@code klass} defined by a non-boot (application) class loader? */
    public static boolean isApplicationClass(Klass klass) {
        return !StaticObject.isNull(klass.getDefiningClassLoader());
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

    /**
     * The value of instance {@code field} on {@code obj} as seen through the acting thread's view, or
     * {@link MemoryModel#ABSENT} when the read is <em>not</em> view-routed — a non-app-owned receiver, a
     * volatile field (whose value lives in the physical slot), or a shared/inactive model. On ABSENT the
     * caller reads the physical slot as before; otherwise it uses the returned value. This is the one
     * place the "should this read go through the view, and if so what" decision lives, so every
     * accessor — bytecode and VM/boot code alike — is a one-liner over it. Boxed for primitive kinds.
     */
    /**
     * Eligibility: is an instance {@code field} of {@code obj} a versioned <em>location</em> — decided
     * purely by the location (an app-owned receiver, a non-volatile field, a live model), independent of
     * whether any value has been staged or committed for it yet. This is the single gate both
     * {@link #readInstance} and {@link #writeInstance} consult, so read and write agree on exactly which
     * locations the model owns. Whether a value <em>exists</em> at the location is a separate question
     * that belongs only to the read (its {@code ABSENT} result); it is never a reason to route a write.
     */
    public static boolean versionsInstanceField(StaticObject obj, Field field) {
        // Hidden fields are VM-injected storage (not guest-declared) holding host-side objects — the
        // EspressoLock in Thread.0toNativeLock, the host Thread in 0hostThread — which are never guest
        // values and never app data. They must stay raw: the hidden reader (getHiddenObjectHelper) is
        // already un-routed, so versioning the write alone would stage a value the reader never sees.
        if (!versionsInstance(obj) || field.isVolatile() || field.isHidden()) {
            return false;
        }
        var ctx = field.getDeclaringKlass().getContext();
        return ctx.getLanguage().isMemoryModelEnabled() && ctx.getMemoryModel().isActive();
    }

    public static Object readInstance(StaticObject obj, Field field) {
        if (!versionsInstanceField(obj, field)) {
            return MemoryModel.ABSENT;
        }
        return field.getDeclaringKlass().getContext().getMemoryModel().readField(obj, field);
    }

    /**
     * The write counterpart of {@link #readInstance}, gated on the <em>same</em> {@link
     * #versionsInstanceField} eligibility: a write to a versioned location is always staged in the acting
     * thread's view — never written to the physical slot — regardless of whether a value has been staged
     * or committed there yet. So the first write to a field (e.g. the VM writing {@code Thread.eetop} while
     * building a thread) creates the versioned cell in the builder's view rather than escaping to the raw
     * heap; the heap slot stays at version 0 (its allocation default). A raw write to a versioned field
     * would be a leak, structurally the same as an unversioned field bypassing the model. Returns {@code
     * true} when it staged the write (the caller does nothing more); {@code false} for a non-versioned
     * location (non-app-owned receiver, volatile field, or shared/inactive model), where the caller then
     * performs the raw physical write.
     */
    public static boolean writeInstance(StaticObject obj, Field field, Object value) {
        if (!versionsInstanceField(obj, field)) {
            return false;
        }
        field.getDeclaringKlass().getContext().getMemoryModel().writeField(obj, field, value);
        return true;
    }

    /**
     * Sync-as-Share (stage-on-access): lazily capture the genesis (version-0) {@code value} of a versioned
     * instance {@code field} of {@code obj} — the value just read from the physical slot because {@link
     * #readInstance} returned {@link MemoryModel#ABSENT}. Gated on the <em>same</em> {@link
     * #versionsInstanceField} eligibility as read/write, so an ABSENT that meant "not a versioned location"
     * (non-app-owned receiver, volatile/hidden field, shared/inactive model) captures nothing and the field
     * stays raw. For a versioned location it captures the value into the acting thread's view, so the next
     * read resolves there instead of reading the live slot again — closing the uncontrolled read-through.
     */
    public static void captureGenesisRead(StaticObject obj, Field field, Object value) {
        if (!versionsInstanceField(obj, field)) {
            return;
        }
        field.getDeclaringKlass().getContext().getMemoryModel().captureRead(obj, field, value);
    }
}
