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
 * A vector clock: one entry per thread, the number of that thread's events this clock has seen. Unlike an
 * epoch it can tell whether another thread's event happens before ours.
 */
final class VectorClock implements Clock {

    // the thread this clock belongs to, whose entry tick advances
    private final int thread;

    private final int[] clock;

    private VectorClock(int thread) {
        this.thread = thread;
        this.clock = new int[thread + 1];
    }

    private VectorClock(int thread, int[] clock) {
        this.thread = thread;
        this.clock = clock;
    }

    /** A thread's clock before its first event. */
    static VectorClock start(int thread) {
        return new VectorClock(thread);
    }

    @Override
    public VectorClock tick() {
        VectorClock c = new VectorClock(thread, clock.clone());
        c.clock[thread] += 1;
        return c;
    }

    @Override
    public VectorClock join(Clock theirs) {
        if (!(theirs instanceof VectorClock v)) {
            throw new IllegalArgumentException("joining a vector clock with " + theirs);
        }

        int longest = Math.max(clock.length, v.clock.length);
        int[] joined = new int[longest];

        System.arraycopy(clock, 0, joined, 0, clock.length);

        for (int i = 0; i < v.clock.length; i++) {
            joined[i] = Math.max(joined[i], v.clock[i]);
        }

        return new VectorClock(thread, joined);
    }

    /**
     * Exact both ways, from our own entry alone. Only our owner advances that entry, so other reaching it
     * means other took in our owner's clock as it was here or later, which covers every other entry too.
     */
    @Override
    public boolean happensBefore(Clock other) {
        if (!(other instanceof VectorClock v)) {
            throw new IllegalArgumentException("comparing a vector clock with " + other);
        }

        int ours = clock[thread];
        int theirs = v.clock.length > thread ? v.clock[thread] : 0;
        return ours <= theirs;
    }
}
