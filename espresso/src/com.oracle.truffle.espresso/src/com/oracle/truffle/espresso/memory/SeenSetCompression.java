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
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds every commit's seen set from the states holding commits, then collapses each region of
 * commits with equal seen sets into its newest commit.
 */
final class SeenSetCompression {

    static boolean COMPRESS = true;

    // how many calls to tryCompress, across every thread, between compressions
    private static final int INTERVAL = 4096;
    private static final AtomicLong calls = new AtomicLong();

    private SeenSetCompression() {
    }

    // compresses on every INTERVAL-th call
    static void tryCompress() {
        if (COMPRESS && calls.incrementAndGet() % INTERVAL == 0) {
            compress();
        }
    }

    private static void compress() {
        Commit.GRAPH.writeLock().lock();
        try {
            SeenSet.reset();

            Map<Commit, SeenSet> holders = holders();
            List<Commit> newestFirst = reachedFrom(holders.keySet());
            newestFirst.sort((c1, c2) -> Integer.compare(c2.generation(), c1.generation()));
            Map<Commit, SeenSet> seenBy = seenSets(newestFirst, holders);

            // newest first, so each region folds into its newest commit
            Set<Commit> absorbed = new HashSet<>();
            for (Commit c : newestFirst) {
                if (!absorbed.contains(c)) {
                    absorbed.addAll(collapseInto(c, seenBy));
                }
            }
        } finally {
            Commit.GRAPH.writeLock().unlock();
        }
    }

    // the states holding each commit
    private static Map<Commit, SeenSet> holders() {
        Map<Commit, SeenSet> holders = new HashMap<>();
        synchronized (SyncState.ALL) {
            for (SyncState s : SyncState.ALL) {
                if (s.commit != null) {
                    holders.merge(s.commit, SeenSet.EMPTY.with(s), SeenSet::union);
                }
            }
        }
        return holders;
    }

    // every commit reachable from the given ones through parents
    private static List<Commit> reachedFrom(Set<Commit> starts) {
        Set<Commit> reached = new HashSet<>(starts);
        Deque<Commit> worklist = new ArrayDeque<>(starts);
        while (!worklist.isEmpty()) {
            for (Commit p : worklist.pop().parents()) {
                if (reached.add(p)) {
                    worklist.push(p);
                }
            }
        }
        return new ArrayList<>(reached);
    }

    // A commit is seen by its holders and by whoever sees one of its children, so children go first.
    // Only reached children count, and those are already in the map by the time their parents are.
    private static Map<Commit, SeenSet> seenSets(List<Commit> newestFirst, Map<Commit, SeenSet> holders) {
        Map<Commit, SeenSet> seenBy = new HashMap<>();
        for (Commit c : newestFirst) {
            SeenSet seen = holders.getOrDefault(c, SeenSet.EMPTY);
            for (Commit child : c.children()) {
                SeenSet childSeen = seenBy.get(child);
                if (childSeen != null) {
                    seen = seen.union(childSeen);
                }
            }
            seenBy.put(c, seen);
        }
        return seenBy;
    }

    // folds the region of ancestors with the same seen set into into, and returns the commits folded
    private static List<Commit> collapseInto(Commit into, Map<Commit, SeenSet> seenBy) {
        SeenSet seen = seenBy.get(into);
        Set<Commit> region = new HashSet<>();
        Set<Commit> newParents = new HashSet<>();
        List<Commit> absorbedInOrder = new ArrayList<>();
        Queue<Commit> ready = new PriorityQueue<>((c1, c2) ->
            Integer.compare(c2.generation(), c1.generation())
        );

        for (Commit p : into.parents()) {
            newParents.add(p);
            if (seen == seenBy.get(p)) {
                // these nodes can be merged together
                ready.add(p);
                region.add(p);
            }
        }

        while(!ready.isEmpty()) {
            Commit ancestor = ready.poll();
            absorbedInOrder.add(ancestor);

            for (Commit p : ancestor.parents()) {
                newParents.add(p);
                if (seen == seenBy.get(p) && region.add(p)) {
                    ready.add(p);
                }
            }
        }

        if (absorbedInOrder.isEmpty()) {
            return absorbedInOrder;
        }

        newParents.removeAll(region);

        // Create a new map for the change set of the node, applying the commits changes in order
        Map<ObjectNumber, ObjectChanges> absorbed = new HashMap<>();
        Set<Commit> movedChildren = new HashSet<>();

        for (Commit c : absorbedInOrder) {
            c.changes().forEach((number, older) ->
                absorbed.merge(number, older, (newer, o) -> newer.createFoldWithOlder(o)));

            for (Commit child : c.children()) {
                if (!region.contains(child) && child != into) {
                    Set<Commit> childsNewParents = new HashSet<Commit>(child.parents());
                    childsNewParents.remove(c);
                    childsNewParents.add(into);
                    child.setParents(childsNewParents);
                    movedChildren.add(child);
                }
            }
        }

        // this commit's own writes win over the absorbed ones
        into.changes().forEach((number, own) ->
            absorbed.merge(number, own, (older, o) -> o.createFoldWithOlder(older)));

        into.setChanges(absorbed);

        // Update out parents to be the parents of all the nodes in the region whose parents were outside the region
        into.setParents(newParents);

        // And tell of the parents outside the region whose children were collapsed that this is now their child
        into.parents().forEach(p -> p.addChild(into));

        // We can lower the generation of this now to whatever the max of the parents was + 1
        // we might get to the root and find null, it which case the generation was 0
        into.setGeneration(into.parents().stream().mapToInt(Commit::generation).max().orElse(0) + 1);

        // And update our children to all the children of the region (we've already update the children's parents to this)
        movedChildren.forEach(into::addChild);

        // We then need to raise the children (and their children's) generation to adhere
        // to the generation invariants, we want to visit the parents of a node before visiting
        // the children so that we only have to raise the child nodes once
        Queue<Commit> childrenToRaise = new PriorityQueue<>((c1, c2) ->
            Integer.compare(c1.generation(), c2.generation())
        );

        // We only have to update the moved children as we merged the region into this node
        childrenToRaise.addAll(movedChildren);

        while (!childrenToRaise.isEmpty()) {
            Commit child = childrenToRaise.poll();
            int newGeneration = child.parents().stream().mapToInt(Commit::generation).max().orElse(0) + 1;
            if (newGeneration > child.generation()) {
                child.setGeneration(newGeneration);
                childrenToRaise.addAll(child.children());
            }
        }

        VersioningStats.onAbsorb(absorbedInOrder.size());
        return absorbedInOrder;
    }
}
