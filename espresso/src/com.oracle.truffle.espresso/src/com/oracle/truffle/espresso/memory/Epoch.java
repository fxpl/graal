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
 * Which thread made a commit, and how many events that thread had before it. Two epochs of one thread are
 * ordered by sequence, as a thread's commits each grow from its previous head. Epochs of different threads
 * say nothing, so a merge does not take theirs into ours.
 */
record Epoch(int thread, int sequence) implements Clock {

    /** A thread's clock before its first commit. */
    static Epoch start(int thread) {
        return new Epoch(thread, 0);
    }

    @Override
    public Epoch tick() {
        return new Epoch(thread, sequence + 1);
    }

    @Override
    public Epoch join(Clock theirs) {
        return this;
    }

    /** Exact within one thread, and false for another thread's epoch, which says nothing either way. */
    @Override
    public boolean happensBefore(Clock other) {
        return other instanceof Epoch e && e.thread == thread && sequence <= e.sequence;
    }
}
