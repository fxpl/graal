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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Put on a {@code Field} handle in {@link com.oracle.truffle.espresso.meta.Meta}: the model never owns
 * that guest field, whoever allocated the object, and every access goes to the raw slot.
 * Eligibility asks whose code allocated an object; this answers, per field, that the program's
 * synchronisation is not what keeps the location consistent. {@code Meta} turns each annotation into
 * a flag on the resolved field ({@link com.oracle.truffle.espresso.impl.Field#markUnversionable}), so
 * the check in {@link Versioning#versionsField} is one boolean.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Unversioned {

    Reason value();

    enum Reason {
        /**
         * The VM writes it from host code behind the program's back (an escaped thread's {@code eetop}
         * as it ends): versioning it second-guesses a protocol the program never took part in, and a
         * write dropped on a conflict corrupts the VM's own bookkeeping. Guest code still synchronises
         * with this state through the model's own events (thread end and the join hook).
         */
        VM_STATE,
        /** The JDK races on it by design ({@code String.hash}), so the race is not the program's. */
        BENIGN_RACE,
        /**
         * Never written once its object is initialised, by its constructor ({@code String.value}) or by
         * the VM ({@code Class.classLoader}), so there is no history to keep, and host code that reads it
         * directly sees what every thread sees.
         */
        IMMUTABLE
    }
}
