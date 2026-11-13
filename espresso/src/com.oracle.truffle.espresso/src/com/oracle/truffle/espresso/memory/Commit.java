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
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A node in the branching model's commit DAG (the prototype's {@code branching::Commit}).
 * {@code changes} are the writes this commit made, per object ({@link ObjectChanges}: to an
 * object's fields, or to an array's elements); {@code parents} is empty for a thread's first
 * commit, has one entry for an ordinary commit, two for a fresh <em>merge</em> commit (whose
 * {@code changes} are empty), and any number once {@link RefCountAbsorption} or
 * {@link SeenSetCompression} has folded ancestors into it.
 *
 * <p>Reference counted: sync states (through {@link SyncState#set}) and child commits each hold a
 * reference. {@link RefCountAbsorption} pulls ancestors whose every reference it can account for
 * into a commit. A count reaching 0 releases the commit's parents in turn ({@link #decRef}).
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

    // A counter of the generation in which this commit was created
    // relative to its parents, for working out relative orders with
    // ancestors when we walk the graph
    // ancestors are always strictly lower in generation.
    private volatile int generation;

    private volatile int refCount;
    private static final AtomicIntegerFieldUpdater<Commit> REF_COUNT =
                AtomicIntegerFieldUpdater.newUpdater(Commit.class, "refCount");


    // commits that have this one as a parent; weak, so dead children drop out
    private final Set<Commit> children = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    // One lock for the whole commit graph: walks (the views' reads and merges) hold the read side, so any
    // number run at once, and anything that rewrites commits other threads can reach holds the write side.
    // Static, as walks start in the views and cross every thread's history.
    static final ReentrantReadWriteLock GRAPH = new ReentrantReadWriteLock();

    private Commit(Map<ObjectNumber, ? extends ObjectChanges> changes, Set<Commit> parents) {
        this.changes = new HashMap<>(changes);
        this.parents = Set.copyOf(parents);

        // prevent a concurrent collapse from changing a parent's generation between reading it and linking in
        GRAPH.readLock().lock();
        try {
            int generation = 0;
            for (Commit p : this.parents) {
                p.incRef();
                generation = Math.max(generation, p.generation);
            }

            this.generation = generation + 1;

            // last, so the commit is complete before a parent can hand it out
            for (Commit p : this.parents) {
                p.children.add(this);
            }
        } finally {
            GRAPH.readLock().unlock();
        }
    }

    /** A snapshot of this commit's children. */
    Set<Commit> children() {
        synchronized (children) {
            return Set.copyOf(children);
        }
    }

    /**
     * The only way a thread makes or folds into a commit. Each view owns one, given its thread's first clock by
     * the model's {@link ClockFactory}, and it keeps that clock, ticked on every commit, fold and merge the thread makes. The clock belongs
     * to the thread, not to its head, which may be a commit another thread made. Used only by its owning
     * thread, so the clock needs no synchronisation.
     */
    static final class Committer {

        // this thread's clock as of its last commit, fold or merge
        private Clock clock;

        Committer(Clock start) {
            this.clock = start;
        }

        Clock clock() {
            return clock;
        }

        /** One more event on this thread, for a head that moved without a commit (adopted or fast-forwarded). */
        void tick() {
            clock = clock.tick();
        }

        /** Take in the clock of a sync state this thread has just pulled from. */
        void join(Clock theirs) {
            clock = clock.join(theirs);
        }

        /** A thread's first commit: no parents. */
        Commit first(Map<ObjectNumber, ObjectChanges> changes) {
            clock = clock.tick();
            return new Commit(changes, Set.of());
        }

        /**
         * {@code newChanges} on top of {@code head}. Folded into {@code head} when its only reference is the
         * sync state holding it, else a new commit with {@code head} as its parent.
         */
        Commit extend(Commit head, Map<ObjectNumber, ObjectChanges> newChanges) {
            clock = clock.tick();
            // under the read side, so a compression on another thread never rewrites the head as we fold
            GRAPH.readLock().lock();
            try {
                if (head.getRefCount() == 1) {
                    head.adoptChanges(newChanges);
                    VersioningStats.onFold();
                    return head;
                }
            } finally {
                GRAPH.readLock().unlock();
            }
            return new Commit(newChanges, Set.of(head));
        }

        /** A merge commit of {@code mine} and {@code theirs}, writing only the values taken for resolved conflicts. */
        Commit merge(Commit mine, Commit theirs, Map<ObjectNumber, FieldChanges> resolved) {
            clock = clock.tick();
            return new Commit(resolved, Set.of(mine, theirs));
        }
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

    /** This commit's changes to object {@code number}, or null if it did not change it. */
    ObjectChanges changesTo(ObjectNumber number) {
        return changes.get(number);
    }

    public int generation() {
        return generation;
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
    private synchronized void adoptChanges(Map<ObjectNumber, ObjectChanges> newChanges) {
        // assert refCount == 1 : "cannot adopt changes if more than one commit can see this commit";
        newChanges.forEach((number, newer) -> {
            changes.merge(number, newer, (older, n) -> {
                older.putAll(n);
                return older;
            });
        });
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

    // The setters below are for SeenSetCompression and RefCountAbsorption, which rewrite the graph

    void setChanges(Map<ObjectNumber, ObjectChanges> changes) {
        this.changes = changes;
    }

    void setParents(Set<Commit> parents) {
        this.parents = Set.copyOf(parents);
    }

    void setGeneration(int generation) {
        this.generation = generation;
    }

    void addChild(Commit child) {
        children.add(child);
    }

    void removeChild(Commit child) {
        children.remove(child);
    }
}
