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
 * One entry in a location's version history: the value published at global {@code counter}. The
 * value is boxed (M2 correctness-first; per-kind storage is a later optimisation). Object-typed
 * values are held as {@code StaticObject} (including {@code StaticObject.NULL}), so a boxed value is
 * never {@code null}.
 *
 * <p>A location's history is an append-only singly-linked chain: {@code prev} points to the
 * immediately older version, or {@code null} for the first. {@link GlobalVersionStore} keeps only
 * the newest node per location (the chain head) and walks {@code prev} newest-first. Nodes are
 * immutable once constructed, so a lock-free reader that observes a head (published via the store's
 * map) sees a fully-built chain via {@code final}-field safe publication. Appending is O(1) -- no
 * array copy -- which is why this replaced a {@code CopyOnWriteArrayList} (whose per-append full copy
 * made a hot location's pushes quadratic).
 */
public record Version(long counter, Object value, Version prev) {
}
