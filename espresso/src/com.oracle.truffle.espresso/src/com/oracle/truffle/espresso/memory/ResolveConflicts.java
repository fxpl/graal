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
 * Put on a {@code Field} handle in {@link com.oracle.truffle.espresso.meta.Meta}: the field stays versioned,
 * but concurrent writes to it are not a conflict. The source races on it by design and any of the written
 * values is acceptable ({@code String.hash}, reflection's lazily filled caches), so the merge resolves the
 * race instead of reporting it. Nothing compares the written values, as equal could mean {@code ==} or
 * {@code equals}, and the annotation is the source's word that it does not matter. {@code Meta} turns each
 * annotation into a flag on the resolved field ({@link com.oracle.truffle.espresso.impl.Field#markConflictResolution}).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ResolveConflicts {

    Resolution value();

    enum Resolution {
        /**
         * The value pulled wins. The merge takes the value carried by the synchronisation the thread
         * performed, so the program's synchronisation decides the outcome rather than the runtime.
         */
        THEIRS
    }
}
