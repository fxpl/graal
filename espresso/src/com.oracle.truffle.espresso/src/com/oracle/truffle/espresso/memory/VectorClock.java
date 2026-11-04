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
 * A vector clock over a sync state's commit history, as in MaTSa and FastTrack: for each thread
 * (view), the number of that thread's newest commit in the history. A thread's commits form a chain
 * (its head only ever moves to a descendant), so a commit is in the history exactly when the clock has
 * reached its number, and branching's merge can ask that with one comparison per commit instead of
 * walking both histories. Kept on a {@link SyncState} by {@link VectorClockBranchingView}; never shared
 * between states, so a thread's own clock is updated in place.
 */
final class VectorClock {

    private int[] ticks = new int[0];

    /** Is {@code c} in the history this clock covers? */
    boolean covers(Commit c) {
        return c.thread() < ticks.length && ticks[c.thread()] >= c.tick();
    }

    /** Record that {@code thread}'s newest commit in the history is now number {@code tick}. */
    void tick(int thread, int tick) {
        grow(thread + 1);
        ticks[thread] = tick;
    }

    /** Take in {@code other}'s history: the element-wise max of the two clocks. */
    void join(VectorClock other) {
        grow(other.ticks.length);
        for (int i = 0; i < other.ticks.length; i++) {
            ticks[i] = Math.max(ticks[i], other.ticks[i]);
        }
    }

    /** Make this clock a copy of {@code other} (reusing the array when it is the same width). */
    void copy(VectorClock other) {
        if (ticks.length == other.ticks.length) {
            System.arraycopy(other.ticks, 0, ticks, 0, ticks.length);
        } else {
            ticks = other.ticks.clone();
        }
    }

    int width() {
        return ticks.length;
    }

    private void grow(int width) {
        if (ticks.length < width) {
            int[] wider = new int[width];
            System.arraycopy(ticks, 0, wider, 0, ticks.length);
            ticks = wider;
        }
    }
}
