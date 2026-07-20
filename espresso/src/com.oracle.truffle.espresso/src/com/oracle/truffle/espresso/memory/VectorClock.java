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

import java.util.Arrays;

/**
 * A vector clock over thread ids: for each thread, the highest commit counter of that thread this
 * clock has observed. It is the branching model's happens-before summary, in the FastTrack / DJIT+
 * data-race-detection sense (Flanagan &amp; Freund, PLDI 2009): a view carries one as its frontier, and
 * each published branch tip (lock state, terminal head) carries one, so the two DAG questions a merge
 * asks are answered without walking the DAG:
 * <ul>
 * <li><b>containment</b> — does this clock already include a given commit (and thus, by counter
 *     monotonicity, all of that commit's ancestry)? A single lookup, {@link #contains(Timestamp)}.</li>
 * <li><b>join</b> — incorporate another clock's knowledge (component-wise max), {@link #join}.</li>
 * </ul>
 *
 * <p>Because a {@link Commit}'s {@link Timestamp} is the FastTrack "epoch" ({@code counter@thread}) of
 * its writer, per-location writer identity needs only the epoch on the commit — full clocks live only
 * on views and published tips, never on every commit.
 *
 * <p><b>Representation.</b> A dense {@code long[]} indexed by thread id ({@code counters[t]} = highest
 * counter of thread {@code t} observed, {@code 0} = none; counters start at {@code 1}). Thread ids are
 * assigned densely from a counter, so this is the standard FastTrack layout: containment and observe
 * are O(1) array ops with <em>no boxing and no hashing</em> — the hot per-commit cost of the previous
 * {@code HashMap<Long,Long>}. (Trade-off: the array is sized to the largest thread id seen, so a
 * program that creates very many threads over its lifetime pays O(maxThreadId) per clock rather than
 * O(distinct threads observed); an open-addressed {@code long}&rarr;{@code long} map would bound that,
 * at more code. Not a concern for the few-thread workloads this targets.)
 *
 * <p>Mutable and single-owner while it is a view's live frontier; a copy is taken (via
 * {@link #VectorClock(VectorClock)}) before it is published on a tip, and the copy is thereafter only
 * read, so it is safely shared across threads through the publishing lock / concurrent map.
 */
final class VectorClock {

    private static final long[] EMPTY = new long[0];

    private long[] counters;

    VectorClock() {
        this.counters = EMPTY;
    }

    /** Copy constructor — used to snapshot a view's frontier onto a published tip or a fork point. */
    VectorClock(VectorClock other) {
        this.counters = other.counters.length == 0 ? EMPTY : Arrays.copyOf(other.counters, other.counters.length);
    }

    /** Whether this clock has observed thread {@code thread} up to at least {@code counter}. */
    boolean contains(long thread, long counter) {
        return thread < counters.length && counters[(int) thread] >= counter;
    }

    /**
     * Whether this clock's history includes the commit with epoch {@code e} — and therefore, by
     * counter monotonicity along a thread's chain, all of that commit's ancestry. This is the FastTrack
     * {@code e ⪯ C} test.
     */
    boolean contains(Timestamp e) {
        return contains(e.thread(), e.counter());
    }

    /** Record that thread {@code thread} has reached commit {@code counter} (a no-op if already ahead). */
    void observe(long thread, long counter) {
        int t = (int) thread;
        if (t >= counters.length) {
            counters = Arrays.copyOf(counters, t + 1);
        }
        if (counter > counters[t]) {
            counters[t] = counter;
        }
    }

    /** Incorporate another clock's knowledge: component-wise max (the happens-before join {@code ⊔}). */
    void join(VectorClock other) {
        long[] o = other.counters;
        if (o.length > counters.length) {
            counters = Arrays.copyOf(counters, o.length);
        }
        for (int t = 0; t < o.length; t++) {
            if (o[t] > counters[t]) {
                counters[t] = o[t];
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (int t = 0; t < counters.length; t++) {
            if (counters[t] != 0) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(t).append(':').append(counters[t]);
                first = false;
            }
        }
        return sb.append('}').toString();
    }
}
