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

/**
 * What a {@link Snapshot} holds for one object: its latest writes, each stamped with the epoch of the
 * commit that made it, so a merge can tell which of two writes to a location another thread has seen.
 * Immutable, as snapshots share them.
 */
sealed interface StampedChanges permits StampedFieldChanges, StampedArrayChanges {

    /** One write, with the epoch of the commit that made it. */
    record Write(Object value, Epoch epoch) {
    }

    /** New changes holding the writes of {@code older} and of these, these winning where both wrote. Neither is modified. */
    StampedChanges createFoldWithOlder(StampedChanges older);
}
