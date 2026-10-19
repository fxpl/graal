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
    static final AtomicLong commits = new AtomicLong();        // Commit nodes created (== resident DAG)
    static final AtomicLong committedChanges = new AtomicLong(); // (location,value) pairs across all commits
                                                                // = dag's retained value-slots (full history)
    static final AtomicLong merges = new AtomicLong();          // merge() calls that were not no-ops

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

    static void onCommit(int changes) {
        if (ENABLED) {
            commits.incrementAndGet();
            committedChanges.addAndGet(changes);
        }
    }

    static void onMerge() {
        if (ENABLED) {
            merges.incrementAndGet();
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

    private static void dump() {
        StringBuilder sb = new StringBuilder("[versioning-stats]");
        if (commits.get() > 0 || merges.get() > 0) {
            sb.append(" branching: commits=").append(commits)
                            .append(" committedChanges=").append(committedChanges)
                            .append(" merges=").append(merges);
        }
        if (gVersions.get() > 0) {
            sb.append(" linear: gVersions=").append(gVersions)
                            .append(" gLocations=").append(gLocations);
            if (collapses.get() > 0) {
                sb.append(" collapses=").append(collapses)
                                .append(" versionsDropped=").append(versionsDropped)
                                .append(" gResident~=").append(gVersions.get() - versionsDropped.get());
            }
        }
        sb.append(" peakStaging=").append(peakStaging);
        System.err.println(sb);
    }
}
