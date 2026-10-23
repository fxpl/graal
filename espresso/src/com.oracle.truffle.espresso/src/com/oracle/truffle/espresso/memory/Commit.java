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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * An immutable node in the branching model's commit DAG (the prototype's {@code branching::Commit}).
 * {@code changes} are the writes published by this commit (boxed values, keyed by
 * {@link VersionedLocation}); {@code parents} has one entry for an ordinary commit, zero for a
 * thread's first commit, and two for a <em>merge</em> commit (whose {@code changes} are empty).
 *
 * <p>Immutable: {@code final} fields plus unmodifiable {@code changes}/{@code parents}, so a commit
 * published into the DAG is safely visible to other threads via final-field semantics — the same
 * immutability {@link Version} relies on for the Linear model.
 */
public class Commit {

    private final Map<VersionedLocation, Object> changes;
    private final List<Commit> parents;

    private volatile int refCount;
    private static final AtomicIntegerFieldUpdater<Commit> REF_COUNT =
                AtomicIntegerFieldUpdater.newUpdater(Commit.class, "refCount");

    public Commit(Map<VersionedLocation, Object> changes, List<Commit> parents) {
        this.changes = Map.copyOf(changes);
        this.parents = List.copyOf(parents);

        for (Commit p : this.parents) {
            p.incRef();
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

    public Map<VersionedLocation, Object> changes() {
        return changes;
    }

    public List<Commit> parents() {
        return parents;
    }

    public void incRef() {
        // don't allow a commits refcount to be bumped back from -1
        int previous = REF_COUNT.getAndIncrement(this);
        assert previous >= 0 : "incRef on a commit already unlinked from the graph";
    }

    public int decRef() {
        return REF_COUNT.decrementAndGet(this);
    }

    public int getRefCount() {
        return refCount;
    }

    void unlinkFromParents() {
        // do not unlink the parents unless the count for this node is already
        // set to 0, we then set the node to -1 to ensure this only runs once
        assert refCount == 0 : "unlinkFromParents on a commit with references or already unlinked";
        for (Commit p : parents) {
            p.decRef();
        }
        decRef();
    }
}
