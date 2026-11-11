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

import java.util.ArrayList;
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

    // how many calls to tryCompress, across every thread, between compressions, from
    // SYNCSHARE_COMPRESS_INTERVAL (default 4096), and 0 turns compression off
    private static final int INTERVAL = intervalFromEnvironment();
    private static final AtomicLong calls = new AtomicLong();

    private SeenSetCompression() {
    }

    private static int intervalFromEnvironment() {
        String interval = System.getenv("SYNCSHARE_COMPRESS_INTERVAL");
        return interval == null ? 4096 : Integer.parseInt(interval);
    }

    // compresses on every INTERVAL-th call
    static void tryCompress() {
        if (INTERVAL > 0 && calls.incrementAndGet() % INTERVAL == 0) {
            compress();
        }
    }

    private static void compress() {
        Commit.GRAPH.writeLock().lock();
        try {
            // collect the seen sets for all of the entry commits pointed
            // to by sync states (locks, threads, etc.)
            Map<Commit, SeenSet> seenBy = new HashMap<>();
            synchronized (SyncState.ALL) {
                // each sync state pointing at a commit gets the next bit position for this run
                int position = 0;
                for (SyncState s : SyncState.ALL) {
                    if (s.commit != null) {
                        // To syncstates maybe be pointing at the same commit
                        seenBy.merge(s.commit, SeenSet.of(position), SeenSet::union);
                        position++;
                    }
                }
            }

            // Walk back from the entry points newest first, so every child is visited before its parents.
            // A commit's seen set is complete when it is visited, and is then unioned into each of its parents.
            List<Commit> newestFirst = new ArrayList<>();
            Queue<Commit> ready = new PriorityQueue<>((c1, c2) ->
                Integer.compare(c2.generation(), c1.generation())
            );
            ready.addAll(seenBy.keySet());

            while (!ready.isEmpty()) {
                Commit c = ready.poll();
                newestFirst.add(c);

                SeenSet seen = seenBy.get(c);
                for (Commit p : c.parents()) {
                    // the first child to reach a parent gives it its seen set and queues it
                    boolean isQueued = seenBy.containsKey(p);
                    seenBy.merge(p, seen, SeenSet::union);
                    if (!isQueued) {
                        ready.add(p);
                    }
                }
            }

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

    // folds the region of ancestors with the same seen set into 'into', and returns the commits absorbed
    private static List<Commit> collapseInto(Commit into, Map<Commit, SeenSet> seenBy) {
        SeenSet seen = seenBy.get(into);
        Set<Commit> region = new HashSet<>();
        List<Commit> absorbedInOrder = new ArrayList<>();
        Queue<Commit> ready = new PriorityQueue<>((c1, c2) ->
            Integer.compare(c2.generation(), c1.generation())
        );

        // for each parent we reach, how many references come from inside the region we are walking
        Map<Commit, Integer> reachedParentsRefs = new HashMap<>();

        for (Commit p : into.parents()) {
            reachedParentsRefs.merge(p, 1, Integer::sum);
            if (seen.equals(seenBy.get(p))) {
                // these nodes can be merged together
                ready.add(p);
                region.add(p);
            }
        }

        while(!ready.isEmpty()) {
            Commit ancestor = ready.poll();
            absorbedInOrder.add(ancestor);

            for (Commit p : ancestor.parents()) {
                reachedParentsRefs.merge(p, 1, Integer::sum);
                if (seen.equals(seenBy.get(p)) && region.add(p)) {
                    ready.add(p);
                }
            }
        }

        if (absorbedInOrder.isEmpty()) {
            return absorbedInOrder;
        }

        // what is left are the parents outside the region
        reachedParentsRefs.keySet().removeAll(region);

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
                    // a child already pointing at into keeps its one edge, so into only gains new ones
                    if (childsNewParents.add(into)) {
                        into.incRef();
                    }
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
        into.setParents(reachedParentsRefs.keySet());

        // the commits that pointed at each outside parent are now just into, so it keeps one reference
        reachedParentsRefs.forEach((p, refs) -> {
            for (int i = 1; i < refs; i++) {
                p.decRef();
            }
        });

        // And tell of the parents outside the region whose children were collapsed that this is now their child
        into.parents().forEach(p -> p.addChild(into));

        // the absorbed commits are gone, so their parents lose them as children
        for (Commit c : absorbedInOrder) {
            c.parents().forEach(p -> p.removeChild(c));
        }

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
