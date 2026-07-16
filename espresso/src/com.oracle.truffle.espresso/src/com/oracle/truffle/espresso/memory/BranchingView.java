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
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A guest thread's view in the branching model (the prototype's {@code branching::LocalVersionStore},
 * eager variant): a {@code head} into the commit DAG, a {@code staging} map of uncommitted writes, and
 * a materialised {@code lastWriter} cache giving O(1) committed reads. This is the <b>eager</b> policy
 * — the paper's branching model — so a merge diffs the two branches at their lowest common ancestor
 * and reports a conflict <em>at the merge</em>; reads never conflict.
 *
 * <p>Not thread-safe, and does not need to be: a view is owned by one guest thread. Commits are
 * immutable {@link Commit} nodes shared across views (safe publication via final fields).
 */
public final class BranchingView implements MemoryView {

    private final long threadId;
    private long counter;                                  // this thread's commit counter (Timestamp)
    private Commit head;                                   // tip of this thread's branch (null = none)
    private final Map<VersionedLocation, Object> staging = new HashMap<>();
    private final Map<VersionedLocation, Commit> lastWriter = new HashMap<>();  // eager committed cache
    private String pendingConflict;

    public BranchingView(long threadId) {
        this.threadId = threadId;
    }

    Commit head() {
        return head;
    }

    boolean stagingEmpty() {
        return staging.isEmpty();
    }

    void stage(VersionedLocation loc, Object value) {
        staging.put(loc, value);
    }

    /** Staged value, else the committed value (materialised in lastWriter), else ABSENT (read heap). */
    Object read(VersionedLocation loc) {
        Object staged = staging.get(loc);
        if (staged != null) {
            return staged;
        }
        Commit writer = lastWriter.get(loc);
        return writer != null ? writer.changes().get(loc) : MemoryModel.ABSENT;
    }

    /** Turn staged writes into a new commit on this thread's branch (no-op if nothing staged). */
    void commitStaging() {
        if (staging.isEmpty()) {
            return;
        }
        Commit c = new Commit(new Timestamp(threadId, ++counter), new HashMap<>(staging),
                        head == null ? List.of() : List.of(head), false);
        for (VersionedLocation loc : c.changes().keySet()) {
            lastWriter.put(loc, c);
        }
        staging.clear();
        head = c;
    }

    /** Fork: inherit another thread's branch tip and committed cache (spawn: child adopts parent). */
    void adoptHistory(BranchingView other) {
        this.head = other.head;
        this.lastWriter.clear();
        this.lastWriter.putAll(other.lastWriter);
    }

    /**
     * Merge {@code other} into this branch (eager). Finds the lowest common ancestor, diffs both
     * branches' writes since the LCA, and — if the same location was written on both — returns that
     * location as a conflict (creating a conflicted merge commit and advancing head). On success,
     * updates the committed cache for every location touched on either branch and advances head to a
     * clean merge commit. {@code null} means no conflict. Assumes staging is empty (callers commit
     * first, as the protocol does at every sync action).
     */
    VersionedLocation mergeWithCommit(Commit other) {
        assert staging.isEmpty();
        if (other == null || other == head) {
            return null;
        }
        Commit lca = lowestCommonAncestor(head, other);
        Map<VersionedLocation, Commit> branchA = writesSince(head, lca);
        Map<VersionedLocation, Commit> branchB = writesSince(other, lca);

        VersionedLocation conflict = null;
        for (Map.Entry<VersionedLocation, Commit> e : branchA.entrySet()) {
            Commit b = branchB.get(e.getKey());
            if (b != null && b != e.getValue()) {   // both branches wrote it since the LCA
                conflict = e.getKey();
                break;
            }
        }

        Commit merge = new Commit(new Timestamp(threadId, ++counter), Map.of(),
                        List.of(head, other), conflict != null);
        head = merge;
        if (conflict != null) {
            return conflict;
        }
        // No conflict: fold both branches' latest writers into the committed cache.
        branchA.forEach(lastWriter::put);
        branchB.forEach(lastWriter::put);
        return null;
    }

    // --- DAG helpers (ported from gitmem branching/eager) ---

    /** Latest writer of each location on the branch from {@code tip} back to (excluding) {@code lca}. */
    private static Map<VersionedLocation, Commit> writesSince(Commit tip, Commit lca) {
        Map<VersionedLocation, Commit> out = new HashMap<>();
        Set<Commit> visited = new HashSet<>();
        Map<Commit, Boolean> reachMemo = new HashMap<>();
        Deque<Commit> stack = new ArrayDeque<>();
        if (tip != null) {
            stack.push(tip);
        }
        while (!stack.isEmpty()) {
            Commit c = stack.pop();
            if (c == null || !visited.add(c)) {
                continue;
            }
            if (lca != null && (c == lca || !canReach(c, lca, reachMemo))) {
                continue;   // stop at / beyond the LCA (the shared prefix)
            }
            for (VersionedLocation loc : c.changes().keySet()) {
                out.putIfAbsent(loc, c);   // first (newest, DFS from tip) writer dominates
            }
            for (Commit p : c.parents()) {
                stack.push(p);
            }
        }
        return out;
    }

    /** Whether {@code target} is reachable from {@code from} by following parents (memoised). */
    private static boolean canReach(Commit from, Commit target, Map<Commit, Boolean> memo) {
        if (from == null) {
            return false;
        }
        if (from == target) {
            return true;
        }
        Boolean cached = memo.get(from);
        if (cached != null) {
            return cached;
        }
        boolean r = false;
        for (Commit p : from.parents()) {
            if (canReach(p, target, memo)) {
                r = true;
                break;
            }
        }
        memo.put(from, r);
        return r;
    }

    /** Lowest common ancestor of two commits in the DAG, or null if none (disjoint histories). */
    private static Commit lowestCommonAncestor(Commit a, Commit b) {
        if (a == null || b == null) {
            return null;
        }
        if (a == b) {
            return a;
        }
        // Collect all ancestors of a (inclusive), then BFS from b for the first hit.
        Set<Commit> ancestorsOfA = new HashSet<>();
        Deque<Commit> q = new ArrayDeque<>();
        q.add(a);
        while (!q.isEmpty()) {
            Commit c = q.poll();
            if (c == null || !ancestorsOfA.add(c)) {
                continue;
            }
            q.addAll(c.parents());
        }
        Set<Commit> visited = new HashSet<>();
        q.add(b);
        while (!q.isEmpty()) {
            Commit c = q.poll();
            if (c == null || !visited.add(c)) {
                continue;
            }
            if (ancestorsOfA.contains(c)) {
                return c;   // first common ancestor reached from b
            }
            q.addAll(c.parents());
        }
        return null;
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
