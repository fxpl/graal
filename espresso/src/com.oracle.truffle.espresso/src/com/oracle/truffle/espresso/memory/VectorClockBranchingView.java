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

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * A branching view that finds each side's own commits with vector clocks, as in MaTSa and FastTrack,
 * instead of walking both histories in generation order ({@code --java.BranchingAncestry=clocks}).
 * Every sync state keeps a {@link VectorClock} over its commit's history, and every commit this view
 * makes is stamped with the view's index and its number in the view's chain, so "is this commit in
 * that history?" is one comparison. The clock follows the commit everywhere it goes: ticked when this
 * view commits, joined when its head takes in another history, copied when it pushes (which also
 * covers spawn, a push into the child's state).
 */
final class VectorClockBranchingView extends BranchingView {

    // this view's slot in the vector clocks, and the number of its newest commit
    private final int index;
    private int tick;

    VectorClockBranchingView(Supplier<SyncState> bindState, AtomicLong deadObjectCount, int index) {
        super(bindState, deadObjectCount);
        this.index = index;
    }

    private static VectorClock clockOf(SyncState state) {
        if (state.clock == null) {
            state.clock = new VectorClock();
        }
        return state.clock;
    }

    @Override
    Commit nextCommit(Commit head, Map<ObjectNumber, ObjectChanges> changes) {
        tick++;
        // a fold keeps head's older number, which the clock still covers
        Commit next = head == null ? Commit.first(changes, index, tick) : head.extend(changes, index, tick);
        clockOf(state()).tick(index, tick);
        return next;
    }

    @Override
    Commit mergeCommit(Commit mine, Commit theirs) {
        tick++;
        Commit merge = Commit.merge(mine, theirs, index, tick);
        clockOf(state()).tick(index, tick);
        return merge;
    }

    @Override
    void divergence(SyncState from, Commit myHead, Commit theirHead, List<Commit> mineOnly, List<Commit> theirsOnly) {
        VectorClock mine = clockOf(state());
        VectorClock theirs = clockOf(from);
        if (mine.covers(theirHead)) {
            // they are in our history: nothing of theirs to take in
            return;
        }
        notCoveredBy(theirHead, mine, theirsOnly);
        if (!theirs.covers(myHead)) {
            notCoveredBy(myHead, theirs, mineOnly);
        }
    }

    // The commits in head's history that clock does not cover, newest first: walk back from head,
    // stopping at any commit the clock has
    private static void notCoveredBy(Commit head, VectorClock clock, List<Commit> out) {
        Set<Commit> seen = new HashSet<>();
        Deque<Commit> stack = new ArrayDeque<>();
        seen.add(head);
        stack.push(head);
        while (!stack.isEmpty()) {
            Commit c = stack.pop();
            out.add(c);
            for (Commit p : c.parents()) {
                if (!clock.covers(p) && seen.add(p)) {
                    stack.push(p);
                }
            }
        }
        out.sort(Comparator.comparingInt(Commit::generation).reversed());
    }

    @Override
    void tookIn(SyncState from) {
        if (from.clock != null) {
            clockOf(state()).join(from.clock);
        }
    }

    @Override
    void pushedTo(SyncState to) {
        clockOf(to).copy(clockOf(state()));
    }
}
