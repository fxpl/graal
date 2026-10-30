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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional, host-side counters for the memory footprint of the version-controlled models, enabled by
 * the {@code SYNCSHARE_STATS} environment variable. The versioned structures (branching {@link Commit}
 * nodes and the DAG, linear {@link Version} chains, staging maps) live on the Espresso <em>runtime</em>
 * (host) heap, not the guest heap. These counters measure them directly. Without collapsing, "created"
 * counts are also the resident counts; with {@code --java.MemoryModelCollapse=threshold} the
 * {@code collapses}/{@code versionsDropped} counters report what was reclaimed (so resident linear
 * versions {@code ~= gVersions - versionsDropped}).
 *
 * <p>When disabled (the default) every hook is a single {@code static final} boolean test that the
 * compiler folds away, so the speed benchmarks are unaffected. A summary is printed to host {@code
 * stderr} at JVM shutdown (armed once by whichever versioned model is constructed).
 */
final class VersioningStats {

    static final boolean ENABLED = System.getenv("SYNCSHARE_STATS") != null;

    // Branching.
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
    static final AtomicLong purgedEntries = new AtomicLong();   // commit entries removed because their object died
    static final AtomicLong purgedArrays = new AtomicLong();    // ...of which arrays (one entry per array per commit)
    static final AtomicLong released = new AtomicLong();        // commits whose ref count reached 0 (the decRef cascade)
                                                                // nodes live ~= commits - folds + merges - absorbed - released
    static final AtomicLong cleanerRegistrations = new AtomicLong(); // objects registered with the purge Cleaner
    static final AtomicLong markedForPurging = new AtomicLong();     // ...of which the GC found dead (Cleaner ran)
    static final AtomicLong readWalks = new AtomicLong();       // reads not in staging that walked the commit history
    static final AtomicLong readWalkMisses = new AtomicLong();  // ...of which found no committed value (ABSENT)
    static final AtomicLong readWalkVisited = new AtomicLong(); // commits visited by all walks
    static final AtomicLong readWalkMissVisited = new AtomicLong(); // ...of which by walks that found nothing
    static final AtomicLong readCacheHits = new AtomicLong();   // reads answered by a view's read cache (no walk)
    static final AtomicLong readCacheClears = new AtomicLong(); // times a read cache hit its cap and was cleared

    // Linear.
    static final AtomicLong gVersions = new AtomicLong();       // Version nodes ever appended to g
    static final AtomicLong gLocations = new AtomicLong();      // distinct versioned locations in g
    static final AtomicLong collapses = new AtomicLong();       // collapse() runs (--java.MemoryModelCollapse)
    static final AtomicLong versionsDropped = new AtomicLong(); // Version nodes reclaimed by collapse
                                                                // (resident ~= gVersions - versionsDropped)

    // Both.
    static final AtomicLong peakStaging = new AtomicLong();     // largest staging map seen (residency)

    private static final AtomicBoolean ARMED = new AtomicBoolean();

    private VersioningStats() {
    }

    /** Register the shutdown dump once (called by a versioned model's constructor). No-op when disabled. */
    static void arm() {
        if (ENABLED && ARMED.compareAndSet(false, true)) {
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
            purgedEntries.addAndGet(fieldEntriesRemoved + arraysRemoved);
            purgedArrays.addAndGet(arraysRemoved);
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

    static void onReadCacheHit() {
        if (ENABLED) {
            readCacheHits.incrementAndGet();
        }
    }

    static void onReadCacheClear() {
        if (ENABLED) {
            readCacheClears.incrementAndGet();
        }
    }

    static void onGVersion(int distinctLocations) {
        if (ENABLED) {
            gVersions.incrementAndGet();
            observe(gLocations, distinctLocations);
        }
    }

    static void onCollapse(long dropped) {
        if (ENABLED) {
            collapses.incrementAndGet();
            versionsDropped.addAndGet(dropped);
        }
    }

    static void observeStaging(int size) {
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

    /** Commit nodes ever created: every commit that did not fold, plus every merge commit. */
    private static long nodesCreated() {
        return commits.get() - folds.get() + merges.get();
    }

    private static void dump() {
        StringBuilder sb = new StringBuilder("[versioning-stats]");
        if (commits.get() > 0 || merges.get() > 0 || folds.get() > 0 || cleanerRegistrations.get() > 0) {
            long hitWalks = readWalks.get() - readWalkMisses.get();
            sb.append(" branching")
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
                            .append(" purgedEntries=").append(purgedEntries)
                            .append(" purgedArrays=").append(purgedArrays)
                            .append("\n  reads:    walks=").append(readWalks)
                            .append(" misses=").append(readWalkMisses)
                            .append(" visited=").append(readWalkVisited)
                            .append(" missVisited=").append(readWalkMissVisited)
                            .append(" avgHitWalk=").append(hitWalks == 0 ? 0 : (readWalkVisited.get() - readWalkMissVisited.get()) / hitWalks)
                            .append(" avgMissWalk=").append(readWalkMisses.get() == 0 ? 0 : readWalkMissVisited.get() / readWalkMisses.get())
                            .append("\n  cache:    hits=").append(readCacheHits)
                            .append(" clears=").append(readCacheClears);
        }
        if (gVersions.get() > 0) {
            sb.append(" linear")
                            .append("\n  g:        versions=").append(gVersions)
                            .append(" locations=").append(gLocations);
            if (collapses.get() > 0) {
                sb.append("\n  collapse: collapses=").append(collapses)
                                .append(" versionsDropped=").append(versionsDropped)
                                .append(" resident~=").append(gVersions.get() - versionsDropped.get());
            }
        }
        sb.append("\n  staging:  peak=").append(peakStaging);
        System.err.println(sb);
    }
}
