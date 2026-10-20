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
public record Commit(Timestamp id, Map<VersionedLocation, Object> changes, List<Commit> parents) {

    public Commit {
        changes = Map.copyOf(changes);
        parents = List.copyOf(parents);
    }

    // Identity equality: a Commit is a NODE in the DAG -- two distinct node objects are distinct even
    // with identical content. This is not just semantics: the record's default value-based equals/
    // hashCode recurse through `parents`, so hashing one commit would hash its entire ancestry (O(DAG)),
    // making the O(1) `visited`-set operations in a DAG walk O(DAG) each and the walk O(DAG^2). All
    // uses compare commits by identity (`==`) or key maps by VersionedLocation/StaticObject, so identity
    // equality here is both correct and what the callers already assume.
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
}
