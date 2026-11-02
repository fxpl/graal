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

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional, host-side counters for the memory footprint of the version-controlled models, enabled by
 * the {@code SYNCSHARE_STATS} environment variable. The versioned structures (branching {@link Commit}
 * nodes and the DAG or chain they form, staging maps) live on the Espresso <em>runtime</em>
 * (host) heap, not the guest heap. These counters measure them directly.
 *
 * <p>When disabled (the default) every hook is a single {@code static final} boolean test that the
 * compiler folds away, so the speed benchmarks are unaffected. A summary is printed to host {@code
 * stderr} at JVM shutdown (armed once by whichever versioned model is constructed).
 */
final class VersioningStats {

    static final boolean ENABLED = System.getenv("SYNCSHARE_STATS") != null;

    // The commit history (both models).
    static final AtomicLong commits = new AtomicLong();        // commitStaging calls that committed something
                                                                // (folded or not)
    static final AtomicLong committedChanges = new AtomicLong(); // staged writes committed, summed over commits
    static final AtomicLong committedArrays = new AtomicLong();  // arrays with writes in a commit, summed over commits
    static final AtomicLong committedArrayElements = new AtomicLong(); // array elements written, summed over commits
    static final AtomicLong merges = new AtomicLong();          // merge commits created
    static final AtomicLong folds = new AtomicLong();           // ...commits that folded into an exclusive head in
                                                                // place (no new node). New nodes ~= commits - folds + merges
    static final AtomicLong absorbed = new AtomicLong();        // commits absorbed into a descendant by compression
                                                                // (resident nodes <= commits - folds + merges - absorbed)
    static final AtomicLong purgedFields = new AtomicLong();    // committed field writes removed because their object died
    static final AtomicLong purgedArrays = new AtomicLong();    // committed arrays removed because they died (one per array per commit)
    static final AtomicLong purgeRuns = new AtomicLong();       // purgeObjects calls
    static final AtomicLong drainedStagedFields = new AtomicLong(); // staged field writes dropped because their object died
    static final AtomicLong drainedArrays = new AtomicLong();   // array working copies dropped because their array died
    static final AtomicLong drainedArrayElements = new AtomicLong(); // ...and their written elements not yet committed
    static final AtomicLong released = new AtomicLong();        // commits whose ref count reached 0 (the decRef cascade)
                                                                // nodes live ~= commits - folds + merges - absorbed - released
    static final AtomicLong cleanerRegistrations = new AtomicLong(); // objects registered with the purge Cleaner
    static final AtomicLong markedForPurging = new AtomicLong();     // ...of which the GC found dead (Cleaner ran)
    static final AtomicLong readWalks = new AtomicLong();       // reads not in staging that walked the commit history
    static final AtomicLong readWalkMisses = new AtomicLong();  // ...of which found no committed value (version 0)
    static final AtomicLong readWalkVisited = new AtomicLong(); // commits visited by all walks
    static final AtomicLong readWalkMissVisited = new AtomicLong(); // ...of which by walks that found nothing
    static final AtomicLong eligibleObjects = new AtomicLong();  // objects (not arrays) allocated eligible for versioning
    static final AtomicLong eligibleArrays = new AtomicLong();   // arrays allocated eligible for versioning
    static final AtomicLong versionedObjects = new AtomicLong(); // ...of which objects that were versioned (they escaped)
    static final AtomicLong versionedArrays = new AtomicLong();  // ...of which arrays that were versioned (they escaped)
    static final AtomicLong workingFieldHits = new AtomicLong(); // field reads answered by a view's working value (no walk)
    static final AtomicLong workingArrayHits = new AtomicLong(); // array accesses that found the view's working copy
    static final AtomicLong workingArrayBuilds = new AtomicLong(); // ...and that had to build it from the history

    // Linear.
    static final AtomicLong pulls = new AtomicLong();           // pulls that moved the base to a newer head
    static final AtomicLong pulledCommits = new AtomicLong();   // ...commits they walked and installed, summed

    // Both.
    static final AtomicLong peakStaging = new AtomicLong();     // largest staging map seen (residency)

    // Every sync state still reachable, so the dump can count them by kind and the commits they pin.
    // Weak: a sync state whose object died drops out on its own.
    private static final Set<SyncState> syncStates = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static final AtomicBoolean ARMED = new AtomicBoolean();
    private static volatile String modelName = ""; // the model that armed the dump, for its heading

    private VersioningStats() {
    }

    /** Register the shutdown dump once (called by a versioned model's constructor). No-op when disabled. */
    static void arm(String model) {
        if (ENABLED && ARMED.compareAndSet(false, true)) {
            modelName = model;
            Runtime.getRuntime().addShutdownHook(new Thread(VersioningStats::dump, "versioning-stats-dump"));
        }
    }

    static void onCommit(int fieldChanges, int arrays, long arrayElements) {
        if (ENABLED) {
            commits.incrementAndGet();
            committedChanges.addAndGet(fieldChanges);
            committedArrays.addAndGet(arrays);
            committedArrayElements.addAndGet(arrayElements);
        }
    }

    static void onMerge() {
        if (ENABLED) {
            merges.incrementAndGet();
        }
    }

    static void onFold() {
        if (ENABLED) {
            folds.incrementAndGet();
        }
    }

    static void onAbsorb(int commitsAbsorbed) {
        if (ENABLED) {
            absorbed.addAndGet(commitsAbsorbed);
        }
    }

    static void onPurge(int fieldEntriesRemoved, int arraysRemoved) {
        if (ENABLED) {
            purgeRuns.incrementAndGet();
            purgedFields.addAndGet(fieldEntriesRemoved);
            purgedArrays.addAndGet(arraysRemoved);
        }
    }

    static void onDrainStagedFields(long fields) {
        if (ENABLED) {
            drainedStagedFields.addAndGet(fields);
        }
    }

    static void onDrainArray(long writtenElements) {
        if (ENABLED) {
            drainedArrays.incrementAndGet();
            drainedArrayElements.addAndGet(writtenElements);
        }
    }

    static void onRelease() {
        if (ENABLED) {
            released.incrementAndGet();
        }
    }

    static void onCleanerRegistered() {
        if (ENABLED) {
            cleanerRegistrations.incrementAndGet();
        }
    }

    /** Called on the Cleaner's thread, after a GC found a registered object unreachable. */
    static void onMarkedForPurging() {
        if (ENABLED) {
            markedForPurging.incrementAndGet();
        }
    }

    static void onReadWalk(int visited, boolean found) {
        if (ENABLED) {
            readWalks.incrementAndGet();
            readWalkVisited.addAndGet(visited);
            if (!found) {
                readWalkMisses.incrementAndGet();
                readWalkMissVisited.addAndGet(visited);
            }
        }
    }

    static void onSyncStateCreated(SyncState state) {
        if (ENABLED) {
            syncStates.add(state);
        }
    }

    static void onEligible(boolean array) {
        if (ENABLED) {
            (array ? eligibleArrays : eligibleObjects).incrementAndGet();
        }
    }

    static void onVersioned(boolean array) {
        if (ENABLED) {
            (array ? versionedArrays : versionedObjects).incrementAndGet();
        }
    }

    static void onWorkingFieldHit() {
        if (ENABLED) {
            workingFieldHits.incrementAndGet();
        }
    }

    static void onWorkingArrayHit() {
        if (ENABLED) {
            workingArrayHits.incrementAndGet();
        }
    }

    static void onWorkingArrayBuild() {
        if (ENABLED) {
            workingArrayBuilds.incrementAndGet();
        }
    }

    static void onPull(int commitsWalked) {
        if (ENABLED) {
            pulls.incrementAndGet();
            pulledCommits.addAndGet(commitsWalked);
        }
    }

    static void observeStaging(long size) {
        if (ENABLED) {
            observe(peakStaging, size);
        }
    }

    private static void observe(AtomicLong peak, long value) {
        long cur;
        while (value > (cur = peak.get()) && !peak.compareAndSet(cur, value)) {
            // retry until we win the CAS or someone else recorded a higher peak
        }
    }

    // For each kind of sync state still alive: how many, how many hold a commit, and how many distinct
    // commits those are; then the distinct commits pinned across all kinds.
    private static String syncStateSummary() {
        // clear sync states of objects that have died, so "live" counts only those still reachable
        System.gc();
        Map<SyncState.Kind, int[]> counts = new EnumMap<>(SyncState.Kind.class);
        Map<SyncState.Kind, Set<Commit>> held = new EnumMap<>(SyncState.Kind.class);
        Set<Commit> pinned = new HashSet<>();
        synchronized (syncStates) {
            for (SyncState s : syncStates) {
                counts.computeIfAbsent(s.kind, k -> new int[2])[0]++;
                Commit c = s.commit;
                if (c != null) {
                    counts.get(s.kind)[1]++;
                    held.computeIfAbsent(s.kind, k -> new HashSet<>()).add(c);
                    pinned.add(c);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        counts.forEach((kind, n) -> sb.append(' ').append(kind.name().toLowerCase())
                        .append("=").append(n[0]).append('/').append(n[1]).append('/').append(held.getOrDefault(kind, Set.of()).size()));
        return sb.append(" pinnedCommits=").append(pinned.size()).append(" (live/holding/distinct)").toString();
    }

    /** Commit nodes ever created: every commit that did not fold, plus every merge commit. */
    private static long nodesCreated() {
        return commits.get() - folds.get() + merges.get();
    }

    private static void dump() {
        StringBuilder sb = new StringBuilder("[versioning-stats]");
        if (commits.get() > 0 || merges.get() > 0 || folds.get() > 0 || cleanerRegistrations.get() > 0) {
            long hitWalks = readWalks.get() - readWalkMisses.get();
            sb.append(' ').append(modelName)
                            .append("\n  commits:  commits=").append(commits)
                            .append(" committedChanges=").append(committedChanges)
                            .append(" committedArrays=").append(committedArrays)
                            .append(" committedArrayElements=").append(committedArrayElements)
                            .append(" merges=").append(merges)
                            .append(" folds=").append(folds)
                            .append("\n  nodes:    created=").append(nodesCreated())
                            .append(" absorbed=").append(absorbed)
                            .append(" released=").append(released)
                            .append(" live=").append(nodesCreated() - absorbed.get() - released.get())
                            .append("\n  dead:     cleanerRegistrations=").append(cleanerRegistrations)
                            .append(" markedForPurging=").append(markedForPurging)
                            .append(" purgedFields=").append(purgedFields)
                            .append(" purgedArrays=").append(purgedArrays)
                            .append(" purgeRuns=").append(purgeRuns)
                            .append(" drainedStagedFields=").append(drainedStagedFields)
                            .append(" drainedArrays=").append(drainedArrays)
                            .append(" drainedArrayElements=").append(drainedArrayElements)
                            .append("\n  reads:    walks=").append(readWalks)
                            .append(" misses=").append(readWalkMisses)
                            .append(" visited=").append(readWalkVisited)
                            .append(" missVisited=").append(readWalkMissVisited)
                            .append(" avgHitWalk=").append(hitWalks == 0 ? 0 : (readWalkVisited.get() - readWalkMissVisited.get()) / hitWalks)
                            .append(" avgMissWalk=").append(readWalkMisses.get() == 0 ? 0 : readWalkMissVisited.get() / readWalkMisses.get());
            if (pulls.get() > 0) {
                sb.append("\n  pulls:    pulls=").append(pulls)
                                .append(" commits=").append(pulledCommits)
                                .append(" avgCommits=").append(pulledCommits.get() / pulls.get());
            }
            sb.append("\n  sharing:  objects versioned/eligible=").append(versionedObjects).append('/').append(eligibleObjects)
                            .append(" arrays versioned/eligible=").append(versionedArrays).append('/').append(eligibleArrays)
                            .append("\n  working:  fieldHits=").append(workingFieldHits)
                            .append(" arrayHits=").append(workingArrayHits)
                            .append(" arrayBuilds=").append(workingArrayBuilds)
                            .append("\n  sync:    ").append(syncStateSummary());
        }
        sb.append("\n  staging:  peak=").append(peakStaging);
        System.err.println(sb);
    }
}
