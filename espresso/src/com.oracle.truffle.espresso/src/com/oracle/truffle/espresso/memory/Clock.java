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
 * A logical clock stamped on every commit, so two commits can sometimes be ordered without walking the
 * graph. Immutable: each operation returns a new clock. A clock may know less than the graph does, and
 * says so with {@link Order#UNKNOWN}, after which the caller walks.
 */
interface Clock {

    /** How this clock's commit stands to another's. */
    enum Order {
        /** This commit is the other or one of its ancestors. */
        BEFORE_OR_SAME,
        /** The other commit is one of this commit's ancestors. */
        AFTER,
        /** Neither commit is an ancestor of the other. */
        CONCURRENT,
        /** This clock cannot tell. */
        UNKNOWN
    }

    /** The clock after one more event on this thread, a commit or a fold into the head. */
    Clock tick();

    /** A clock at least as late as this one and {@code theirs}, for a merge. */
    Clock join(Clock theirs);

    Order compare(Clock other);
}
