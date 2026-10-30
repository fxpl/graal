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
 * The writes one commit made to one object: to its fields, or, for an array, to its elements. An
 * object is always the same kind, so two changes for the same object are always the same kind.
 */
sealed interface ObjectChanges permits FieldChanges, ArrayChanges {

    // A new ObjectChanges holding the writes of older and of these, these winning where both wrote.
    // Neither is modified, as other threads may be reading them.
    ObjectChanges createFoldWithOlder(ObjectChanges older);

    // Fold newer's writes into these, in place. Only on a commit exclusive to its one referrer.
    void putAll(ObjectChanges newer);

    // The number of locations written, for the stats.
    long writeCount();
}
