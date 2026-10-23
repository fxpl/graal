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
import java.util.List;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * A guest thread's view in the branching model (the prototype's {@code branching::LocalVersionStore}):
 * a {@code head} into the commit DAG and a {@code staging} map of uncommitted writes.
 *
 * <p>Not thread-safe, and does not need to be: a view is owned by one guest thread. Commits are
 * immutable {@link Commit} nodes shared across views (safe publication via final fields).
 */
public final class BranchingView implements MemoryView {

    private Commit head;                                 // tip of this thread's branch (null = none)
    private Map<VersionedLocation, Object> staging = new HashMap<>();  // reassigned (moved) on commit
    // Sync-as-Share (stage-on-access): per-app-owned-array working copy, materialised on first access and
    // reused for reads/writes/unwrap this epoch. Flushed (touched indices only) to per-index staging and
    // cleared at each commit, so it rebuilds from post-commit committed state next epoch.
    private final Map<StaticObject, ArrayVersion> arrays = new HashMap<>();
    private String pendingConflict;

    Commit head() {
        return head;
    }

    boolean stagingEmpty() {
        return staging.isEmpty();
    }

    int stagingSize() {
        return staging.size();
    }

    void stage(VersionedLocation loc, Object value) {
        staging.put(loc, value);
        VersioningStats.observeStaging(staging.size());
    }

    // --- Sync-as-Share array working copies (stage-on-access) ---

    /** The acting thread's cached working copy of {@code array}, or {@code null} if not yet materialised. */
    ArrayVersion arrayVersion(StaticObject array) {
        return arrays.get(array);
    }

    /** Cache a freshly materialised working copy of {@code array}. */
    void putArrayVersion(StaticObject array, ArrayVersion av) {
        arrays.put(array, av);
    }

    /**
     * Fold each array working copy's touched indices into per-index {@link ArrayLocation} staging, so the
     * commit/merge machinery sees exactly the elements this thread wrote. Reads (untouched copies) flush
     * nothing. Called just before a commit.
     */
    void flushArrays() {
        for (Map.Entry<StaticObject, ArrayVersion> e : arrays.entrySet()) {
            StaticObject array = e.getKey();
            ArrayVersion av = e.getValue();
            for (int i = av.touched.nextSetBit(0); i >= 0; i = av.touched.nextSetBit(i + 1)) {
                stage(new ArrayLocation(array, i), java.lang.reflect.Array.get(av.host, i));
            }
        }
    }

    /** Drop all working copies; they rebuild from committed state on next access. */
    void clearArrays() {
        arrays.clear();
    }

    /** Staged value, else the committed value, else ABSENT. The shared genesis (version-0) cache is
     *  consulted by the model after this, not here. */
    Object read(VersionedLocation loc) {
        Object staged = staging.get(loc);
        if (staged != null) {
            return staged;
        }

        if (head == null) {
            return MemoryModel.ABSENT;
        }

        // The pathological cases are:
        // 1) the value is all the way back at version 0
        // 2) we have multiple candidates

        // Walk back from the current head through the commit history to find
        // the most recent committed value for this location.
        // We know that there is a unique last writer for each location in the
        // commit history as the merges are conflict free.
        // We have to make sure to find the latest committed value for this location.

        Set<Commit> visited = new HashSet<>();
        Set<Commit> candidates = new HashSet<>();
        Set<Commit> heads = new HashSet<>();
        heads.add(head);

        while (!heads.isEmpty()) {
            Commit current = heads.iterator().next();
            heads.remove(current);

            if (visited.contains(current)) {
                continue;
            }

            visited.add(current);

            // once we find a candidate stop
            if (current.changes().containsKey(loc)) {
                candidates.add(current);
            } else {
                // add the two parents to explore
                heads.addAll(current.parents());
            }
        }

        // If no candidates were found, the location is absent.
        if (candidates.isEmpty()) {
            return MemoryModel.ABSENT;
        }

        // There was only one candidate commit, so just return that
        if (candidates.size() == 1) {
            return candidates.iterator().next().changes().get(loc);
        }

        // We had multiple candidates, so now we need to work out which is the only
        // one which isn't shadowed
        Set<Commit> alive = new HashSet<>(candidates);
        visited = new HashSet<>();

        for (Commit c : candidates) {

            // If we have already visited this commit then we already know about
            // it and it's ancestry, so continue to the next candidate
            if (!alive.contains(c)) {
                continue;
            }

            // Otherwise, start walking back from c to build up a collection of
            // nodes visible from c. We reuse visited so eventually we will
            // end up with just one commit not visible from the others
            Deque<Commit> stack = new ArrayDeque<>(c.parents());
            while (!stack.isEmpty()) {
                Commit x = stack.pop();

                // already went this way
                if (!visited.add(x)) {
                    continue;
                }

                // x is an ancestor of c so it can't be newest
                alive.remove(x);
                stack.addAll(x.parents());
            }
        }

        // alive now holds exactly one commit: the newest writer
        assert alive.size() == 1;
        Commit latest = alive.iterator().next();
        return latest.changes().get(loc);
    }

    /** Turn staged writes into a new commit on this thread's branch (no-op if nothing staged). */
    void commitStaging() {
        // Fold array working-copy writes into per-index staging, then drop the copies (they rebuild from
        // post-commit committed state next epoch). Done before the empty check so a commit whose only
        // writes are array writes still lands.
        flushArrays();
        clearArrays();
        if (staging.isEmpty()) {
            return;
        }
        // Move the staging map into the commit rather than copying it (new HashMap<>(staging)): the
        // commit takes sole ownership and staging gets a fresh empty map, so changes() is still
        // immutable-after-publication (nothing else aliases it) at no copy cost. A fresh empty map also
        // beats staging.clear() (which would retain the old capacity). The single biggest per-commit
        // allocation, gone.
        Map<VersionedLocation, Object> changes = staging;
        staging = new HashMap<>();
        Commit c = new Commit(changes, head == null ? List.of() : List.of(head));
        VersioningStats.onCommit(changes.size());
        head = c;
    }

    /**
     * Fork: start this (fresh) view at another branch's tip. Called on the child at start with the tip
     * the parent captured at spawn (spawn: child adopts parent).
     */
    void adopt(Commit forkPoint) {
        this.head = forkPoint;
    }

    /**
     * Merge the branch tip {@code otherHead} into this branch. Returns a set of locations written
     * by both branches (empty means none). Staging must be empty — every sync action (lock
     * acquire, join, volatile read/write) commits first.
     */
    Set<VersionedLocation> merge(Commit otherHead) {
        assert staging.isEmpty();

        if (head == null) {
            // either pickup the other commit as head (there is nothing to merge)
            // or the genuinely is no operation to do here
            if (otherHead != null) {
                head = otherHead;
            }
            return Set.of();
        }

        if (otherHead == null) {
            // just return as there is nothing to merge
            return Set.of();
        }

        if (otherHead == head) {
            // no point in doing this
            return Set.of();
        }

        // walk back from head and otherHead and collect their history
        Set<Commit> headsHistory = new HashSet<>();
        Set<Commit> visited = new HashSet<>();
        Set<Commit> heads = new HashSet<>();
        heads.add(head);

        while(!heads.isEmpty()) {
            Commit current = heads.iterator().next();
            heads.remove(current);

            if(visited.contains(current)) {
                continue;
            }
            visited.add(current);

            headsHistory.add(current);
            heads.addAll(current.parents());
        }

        Set<Commit> otherHeadsHistory = new HashSet<>();
        visited.clear();
        heads.clear();
        heads.add(otherHead);

        while(!heads.isEmpty()) {
            Commit current = heads.iterator().next();
            heads.remove(current);

            if(visited.contains(current)) {
                continue;
            }
            visited.add(current);

            otherHeadsHistory.add(current);
            heads.addAll(current.parents());
        }

        Set<Commit> mineOnly = new HashSet<>(headsHistory);
        mineOnly.removeAll(otherHeadsHistory);
        Set<Commit> theirsOnly = new HashSet<>(otherHeadsHistory);
        theirsOnly.removeAll(headsHistory);

        Set<VersionedLocation> changedLocations = new HashSet<>();
        for (Commit c : mineOnly) {
            changedLocations.addAll(c.changes().keySet());
        }

        Set<VersionedLocation> conflicts = new HashSet<>();
        for (Commit c : theirsOnly) {
            for (VersionedLocation loc : c.changes().keySet()) {
                if (changedLocations.contains(loc)) {
                    conflicts.add(loc);
                }
            }
        }

        if (!conflicts.isEmpty()) {
            return conflicts;
        }

        // create the merge of the parents
        Commit mergeCommit = new Commit(Map.of(), List.of(head, otherHead));
        head = mergeCommit;
        return Set.of();
    }

    // --- MemoryView: deferred-conflict flag (raised at the bytecode safe point / thread end) ---

    void setPendingConflict(String description) {
        this.pendingConflict = description;
    }

    @Override
    public boolean hasPendingConflict() {
        return pendingConflict != null;
    }

    @Override
    public String takePendingConflict() {
        String c = pendingConflict;
        pendingConflict = null;
        return c;
    }
}
