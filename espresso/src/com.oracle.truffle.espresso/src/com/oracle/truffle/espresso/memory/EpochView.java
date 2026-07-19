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

import java.util.HashMap;
import java.util.Map;

/**
 * A guest thread's view in the branching model's <em>epoch</em> backing (see
 * {@link EpochBranchingModel}). The DAG backing's {@link BranchingView} keeps a commit-DAG head and
 * merges by walking; this keeps the same happens-before frontier {@code clock} but replaces the DAG
 * with a flat {@code seen} table — location &rarr; last writer {@link EpochEntry} (epoch + value) — so
 * a merge is a per-location scan, not a walk.
 *
 * <p>Conflict detection is identical in outcome to {@link BranchingView}: for each write the other
 * branch made that this view has not seen, it is a conflict iff the other branch never saw <em>this
 * view's</em> latest writer of that location (the two writers are concurrent). The only difference is
 * that "the writes the other branch made that I have not seen" is read off the published
 * <em>table</em> (skipping entries my clock already contains) rather than recovered by walking the
 * divergent commits.
 *
 * <p>Not thread-safe (owned by one guest thread). {@link EpochEntry} values are immutable and shared
 * across views/tables by reference; the {@code clock} is copied before publication (copy-on-write).
 */
public final class EpochView implements MemoryView {

    private final long threadId;
    private long counter;                                  // this thread's epoch counter
    private VectorClock clock = new VectorClock();         // happens-before frontier
    private boolean clockShared;                           // clock handed to a tip; copy-on-write next mutation
    private final Map<VersionedLocation, Object> staging = new HashMap<>();
    private final Map<VersionedLocation, EpochEntry> seen = new HashMap<>();  // last writer + value per location
    private String pendingConflict;

    public EpochView(long threadId) {
        this.threadId = threadId;
    }

    /**
     * Publish this view's frontier for a lock/terminal tip. Returns the clock <em>by reference</em> and
     * marks it shared, so no copy is allocated on the sync path; the view copies it lazily the next time
     * it mutates the clock ({@link #ownClock}), so the published instance is never mutated after
     * publication. Mirrors {@link BranchingView#publishClock}.
     */
    VectorClock publishClock() {
        clockShared = true;
        return clock;
    }

    private VectorClock ownClock() {
        if (clockShared) {
            clock = new VectorClock(clock);
            clockShared = false;
        }
        return clock;
    }

    void stage(VersionedLocation loc, Object value) {
        staging.put(loc, value);
        VersioningStats.observeStaging(staging.size());
    }

    /** Staged value, else the last committed value seen for the location, else ABSENT (read heap). */
    Object read(VersionedLocation loc) {
        Object staged = staging.get(loc);
        if (staged != null) {
            return staged;
        }
        EpochEntry entry = seen.get(loc);
        return entry != null ? entry.value() : MemoryModel.ABSENT;
    }

    /**
     * Turn staged writes into one epoch {@code threadId@counter} (the analogue of a commit, but no DAG
     * node): record each staged write in {@code seen} under that epoch and advance the frontier. No-op
     * if nothing staged.
     */
    void commitStaging() {
        if (staging.isEmpty()) {
            return;
        }
        Timestamp epoch = new Timestamp(threadId, ++counter);
        for (Map.Entry<VersionedLocation, Object> e : staging.entrySet()) {
            seen.put(e.getKey(), new EpochEntry(epoch, e.getValue()));
        }
        VersioningStats.onCommit();
        staging.clear();
        ownClock().observe(threadId, counter);
        VersioningStats.observeLastWriter(seen.size());
    }

    /** The view's committed cache, read by the model to publish into a lock/terminal table. */
    Map<VersionedLocation, EpochEntry> seen() {
        return seen;
    }

    /** Snapshot the committed cache for a fork point / terminal tip (stable while the thread runs on). */
    Map<VersionedLocation, EpochEntry> snapshotSeen() {
        return new HashMap<>(seen);
    }

    /** Fork: start this (fresh) view from a parent's snapshot cache and frontier clock. */
    void adopt(Map<VersionedLocation, EpochEntry> forkSeen, VectorClock forkClock) {
        seen.clear();
        seen.putAll(forkSeen);
        ownClock().join(forkClock);
    }

    /**
     * Merge a published branch — its {@code table} (location &rarr; latest writer/value) and frontier
     * {@code otherClock} — into this view (eager). Returns a location written concurrently on both
     * branches (a conflict), else {@code null}. Equivalent to {@link BranchingView#merge}, read off the
     * table instead of walked:
     * <ul>
     * <li>skip entries this view has already seen ({@code clock.contains(epoch)} — the walk's clock
     *     pruning);</li>
     * <li>for an unseen entry, a conflict iff my latest writer of that location was never seen by the
     *     other branch ({@code !otherClock.contains(mine.epoch())});</li>
     * <li>otherwise import the entry (the other's write is causally later).</li>
     * </ul>
     * On a conflict we join the clock but import nothing and report the location — matching the DAG
     * backing's conflicted merge (which advances the frontier but does not adopt the conflicting
     * writes). The frontier is joined regardless: this view now causally follows the publisher.
     */
    VersionedLocation mergeTable(Map<VersionedLocation, EpochEntry> table, VectorClock otherClock) {
        if (table.isEmpty()) {
            return null;
        }
        VersionedLocation conflict = null;
        // Collect the imports first: the clock join below would otherwise mask which entries were unseen.
        Map<VersionedLocation, EpochEntry> imports = new HashMap<>();
        for (Map.Entry<VersionedLocation, EpochEntry> e : table.entrySet()) {
            EpochEntry theirs = e.getValue();
            if (clock.contains(theirs.epoch())) {
                continue;                                  // already seen this writer
            }
            EpochEntry mine = seen.get(e.getKey());
            if (mine != null && !otherClock.contains(mine.epoch())) {
                conflict = e.getKey();                     // concurrent writers -> conflict
                break;
            }
            imports.put(e.getKey(), theirs);
        }
        ownClock().join(otherClock);
        if (conflict != null) {
            return conflict;
        }
        seen.putAll(imports);
        VersioningStats.observeLastWriter(seen.size());
        return null;
    }

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
