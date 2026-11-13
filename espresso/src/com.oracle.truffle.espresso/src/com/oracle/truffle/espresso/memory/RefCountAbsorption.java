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
 * You have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.espresso.memory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Absorbs into a commit the ancestors whose every reference comes from that commit or from what it
 * has already absorbed, using the reference counts.
 */
final class RefCountAbsorption {

    // SYNCSHARE_ABSORB=0 turns absorption off, and it is on otherwise
    private static final boolean shouldAbsorb = !"0".equals(System.getenv("SYNCSHARE_ABSORB"));
    private static final Object ABSORB = new Object();

    private RefCountAbsorption() {
    }

    private static int incAndGetReachCount(Commit p, Map<Commit, Integer> reachedCount) {
        return reachedCount.merge(p, 1, Integer::sum);
    }

    // This is a little hairy, so here is the rough idea:
    // for every ancestor, we can absorb the ancestor into this node
    // if from this node we can account for all the references to that
    // ancestor (essentially there is subgraph with a dominator)
    // absorb into commit T:
    //   for every ancestor, count the edges reaching it from T or from what T has absorbed
    //
    //   start: one edge from T into each of T's parents
    //   repeat:
    //     pick an ancestor A whose count equals its refCount
    //         (every reference to A comes from inside the region)
    //     absorb A into T
    //     add one edge from A into each of A's parents
    //   until no ancestor's count equals its refCount
    //
    //   T's changes   = T's own, over everything absorbed, newest over oldest
    //   T's parents   = the ancestors still counted but not absorbed (the boundary)
    //   each boundary commit: its k edges from the region become one edge from T,
    //                         so drop its count by k - 1
    private static boolean absorbAncestors(Commit into) {
        synchronized (into) {
            Map<Commit, Integer> reachedCount = new HashMap<>();
            Deque<Commit> ready = new ArrayDeque<>();      // commits where every reference is account for
            List<Commit> absorbedInOrder = new ArrayList<>();

            for (Commit p : into.parents()) {
                if (incAndGetReachCount(p, reachedCount) == p.getRefCount()) {
                    ready.push(p);
                }
            }

            // Find all of the ancestors which we can account for the references
            // Store them in an ordered queue so that we can replay them and
            // get the correct outcome for the resulting node
            while(!ready.isEmpty()) {
                Commit ancestor = ready.pop();
                reachedCount.remove(ancestor);
                absorbedInOrder.add(ancestor);

                for (Commit p : ancestor.parents()) {
                    if (incAndGetReachCount(p, reachedCount) == p.getRefCount()) {
                        ready.push(p);
                    }
                }
            }

            if (absorbedInOrder.isEmpty()) {
                return false;
            }

            // Create a new map for the change set of the node, applying the commits changes in order
            Map<ObjectNumber, ObjectChanges> absorbed = new HashMap<>();
            for (Commit ancestor : absorbedInOrder) {
                ancestor.changes().forEach((number, older) ->
                    absorbed.merge(number, older, (newer, o) -> newer.createFoldWithOlder(o)));
            }
            // this commit's own writes win over the absorbed ones
            into.changes().forEach((number, own) ->
                absorbed.merge(number, own, (older, o) -> o.createFoldWithOlder(older)));

            // Now we need to remove as many incoming references as we accounted for
            for (Map.Entry<Commit, Integer> b : reachedCount.entrySet()) {
                for (int i = 1; i < b.getValue(); i++) {
                    b.getKey().decRef();
                }
            }

            into.setChanges(absorbed);
            into.setParents(reachedCount.keySet());

            // the absorbed commits are gone, so their parents lose them as children
            for (Commit ancestor : absorbedInOrder) {
                ancestor.parents().forEach(p -> p.removeChild(ancestor));
            }
            // and the parents outside the region gain into in their place
            into.parents().forEach(p -> p.addChild(into));

            VersioningStats.onAbsorb(absorbedInOrder.size());
            return true;
        }
    }

    static boolean absorbInto(Commit into) {
        // nothing is ours alone so no allocation and no lock
        if (!shouldAbsorb || into.parents().stream().noneMatch(p -> p.getRefCount() == 1)) {
            return false;
        }
        long asked = VersioningStats.now();
        // the read side keeps a compression on another thread from rewriting the graph at the same time
        Commit.GRAPH.readLock().lock();
        try {
            synchronized(ABSORB) {
                long took = VersioningStats.now();
                boolean isAbsorbed = absorbAncestors(into);
                VersioningStats.onAbsorbRun(asked, took, VersioningStats.now());
                return isAbsorbed;
            }
        } finally {
            Commit.GRAPH.readLock().unlock();
        }
    }

    // We assume we are in a conflict free DAG
    static void absorbIntoRecursively(Commit from) {
        // nothing is ours alone so no allocation and no lock
        if (!shouldAbsorb || from.parents().stream().noneMatch(p -> p.getRefCount() == 1)) {
            return;
        }

        Deque<Commit> compressTails = new ArrayDeque<>();
        compressTails.add(from);

        Set<Commit> visited = new HashSet<>();
        // the read side for the whole climb, so a compression never runs between its steps
        Commit.GRAPH.readLock().lock();
        try {
            while (!compressTails.isEmpty()) {
                Commit tail = compressTails.pop();
                if (!visited.add(tail)) {
                    continue;
                }

                if (absorbInto(tail)) {
                    // Only continue if we succeeded in compressing anything, there may be
                    // stuff higher in the DAG but we leave that for a deeper compression task
                    compressTails.addAll(tail.parents());
                }
            }
        } finally {
            Commit.GRAPH.readLock().unlock();
        }
    }
}
