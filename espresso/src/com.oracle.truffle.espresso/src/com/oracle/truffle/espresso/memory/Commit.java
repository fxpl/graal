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
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * A node in the branching model's commit DAG (the prototype's {@code branching::Commit}).
 * {@code changes} are the writes this commit made, per object ({@link ObjectChanges}: to an
 * object's fields, or to an array's elements); {@code parents} is empty for a thread's first
 * commit, has one entry for an ordinary commit, two for a fresh <em>merge</em> commit (whose
 * {@code changes} are empty), and any number once {@link #absorbAncestors} has compressed exclusive
 * ancestors into it.
 *
 * <p>Reference counted: heads, tips and child commits each hold a reference. A commit with a count
 * of 1 is exclusive to its one referrer and may be rewritten: {@link #extend} folds new writes into
 * an exclusive head, and {@link #absorbAncestors} pulls exclusive ancestors into a commit. A count
 * reaching 0 releases the commit's parents in turn ({@link #decRef}).
 *
 * <p>Not immutable: other threads walk {@code changes}/{@code parents} without locking, so rewrites
 * of a commit they may reach build a new map/set and swap the volatile field (changes before
 * parents, so a reader never misses a write). Rewriters synchronize on the commit to exclude each
 * other.
 */
public class Commit {

    // when we eliminate old commits, we will swap in place to avoid a racy read
    // so the changes need to be volatile
    private volatile Map<ObjectNumber, ObjectChanges> changes;
    private volatile Set<Commit> parents;

    private volatile int refCount;
    private static final AtomicIntegerFieldUpdater<Commit> REF_COUNT =
                AtomicIntegerFieldUpdater.newUpdater(Commit.class, "refCount");

    private Commit(Map<ObjectNumber, ObjectChanges> changes, Set<Commit> parents) {
        this.changes = new HashMap<>(changes);
        this.parents = Set.copyOf(parents);

        for (Commit p : this.parents) {
            p.incRef();
        }
    }

    /** A thread's first commit: no parents. (One-parent commits come from {@link #extend}.) */
    static Commit first(Map<ObjectNumber, ObjectChanges> changes) {
        return new Commit(changes, Set.of());
    }

    /** A merge commit of {@code a} and {@code b}: no writes of its own, two parents. */
    static Commit merge(Commit a, Commit b) {
        return new Commit(Map.of(), Set.of(a, b));
    }

    // Identity equality: a Commit is a NODE in the DAG -- two distinct node objects are distinct even
    // with identical content.
    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    boolean isMerge() {
        return parents.size() >= 2;
    }

    public Map<ObjectNumber, ObjectChanges> changes() {
        return changes;
    }

    public Set<Commit> parents() {
        return parents;
    }

    public void incRef() {
        // don't allow a commits refcount to be bumped back from -1
        int previous = REF_COUNT.getAndIncrement(this);
        assert previous >= 0 : "incRef on a commit already unlinked from the graph";
    }

    public int decRef() {
        int remaining = REF_COUNT.decrementAndGet(this);

        if (remaining == 0) {
            // cascade into the parents

            Deque<Commit> dead = new ArrayDeque<>();
            dead.push(this);

            while (!dead.isEmpty()) {
                Commit c = dead.pop();

                boolean marked = REF_COUNT.compareAndSet(c, 0, -1);
                assert marked : "a reference was taken to a commit whose count had reached 0";
                VersioningStats.onRelease();

                for (Commit p : c.parents()) {
                    if (REF_COUNT.decrementAndGet(p) == 0) {
                        dead.push(p);
                    }
                }
            }
        }

        return remaining;
    }

    public int getRefCount() {
        return refCount;
    }

    // The two following methods are synchronized so that we can use an independent
    // thread to purge objects without breaking threads that are compacting nodes together

    // we want to update the node with new changes during commit compaction
    public synchronized void adoptChanges(Map<ObjectNumber, ObjectChanges> newChanges) {
        assert refCount == 1 : "cannot adopt changes if more than one commit can see this commit";
        newChanges.forEach((number, newer) -> {
            changes.merge(number, newer, (older, n) -> {
                older.putAll(n);
                return older;
            });
        });
    }

    Commit extend(Map<ObjectNumber, ObjectChanges> newChanges) {
        if (refCount == 1) {
            // only the caller's head references this commit so adopt the changes
            adoptChanges(newChanges);
            VersioningStats.onFold();
            return this;
        }
        return new Commit(newChanges, Set.of(this));
    }

    // we build up a new filtered map and swap in place, to avoid other threads
    // potentially seeing a partly filtered map
    public synchronized void purgeObjects() {
        Map<ObjectNumber, ObjectChanges> filtered = new HashMap<>();
        int fieldsRemoved = 0;
        int arraysRemoved = 0;
        for (Map.Entry<ObjectNumber, ObjectChanges> e : changes.entrySet()) {
            if (!e.getKey().isDead()) {
                filtered.put(e.getKey(), e.getValue());
            } else if (e.getValue() instanceof FieldChanges f) {
                fieldsRemoved += (int) f.writeCount();
            } else {
                arraysRemoved++;
            }
        }

        VersioningStats.onPurge(fieldsRemoved, arraysRemoved);
        changes = filtered;
    }

    public synchronized boolean absorbAncestors() {
        Set<Commit> newParents = new HashSet<>();
        Map<ObjectNumber, ObjectChanges> absorbed = new HashMap<>();

        int adoptedCount = 0;
        Deque<Commit> ancestors = new ArrayDeque<>(parents);
        while (!ancestors.isEmpty()) {
            Commit ancestor = ancestors.pop();
            if (ancestor.getRefCount() == 1) {
                ancestors.addAll(ancestor.parents());
                // ancestors are reached newest first, so what is already absorbed wins over this one
                ancestor.changes().forEach((number, older) ->
                    absorbed.merge(number, older, (newer, o) -> newer.createFoldWithOlder(o)));
                adoptedCount++;
            } else if (!newParents.add(ancestor)) {
                // two edges collapse into one
                if (ancestor.decRef() == 1) {
                    //     g
                    //   /   \
                    // p1     p2
                    //   \   /
                    //     c
                    // absorbing p1 and p2 leaves two edges into g from c
                    // collapsing them drops g to 1, so g is now exclusively
                    // referenced from c and goes back on the deque to be
                    // absorbed
                    newParents.remove(ancestor);
                    ancestors.push(ancestor);
                }
            }
        }

        if (adoptedCount == 0) {
            return false;
        }

        // this commit's own writes win over the absorbed ones
        changes.forEach((number, own) ->
            absorbed.merge(number, own, (older, o) -> o.createFoldWithOlder(older)));
        changes = absorbed;
        parents = Set.copyOf(newParents); // immutable and compact sets
        VersioningStats.onAbsorb(adoptedCount);

        return true;
    }

    // We assume we are in a conflict free DAG
    public void compressUpwards() {
        Deque<Commit> compressTails = new ArrayDeque<>();
        compressTails.add(this);

        Set<Commit> visited = new HashSet<>();
        while (!compressTails.isEmpty()) {
            Commit tail = compressTails.pop();
            if (!visited.add(tail)) {
                continue;
            }

            if (tail.absorbAncestors()) {
                // Only continue if we succeeded in compressing anything, there may be
                // stuff higher in the DAG but we leave that for a deeper compression task
                compressTails.addAll(tail.parents());
            }
        }
    }
}
