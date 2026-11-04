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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional, host-side counters for the memory footprint of the version-controlled models, enabled by
 * the {@code SYNCSHARE_STATS} or {@code SYNCSHARE_STATS_JSON} environment variable. The versioned
 * structures (branching {@link Commit} nodes and the DAG or chain they form, staging maps) live on the
 * Espresso <em>runtime</em> (host) heap, not the guest heap. These counters measure them directly.
 *
 * <p>When disabled (the default) every hook is a single {@code static final} boolean test that the
 * compiler folds away, so the speed benchmarks are unaffected. At JVM shutdown (armed once by whichever
 * versioned model is constructed) a summary is printed to host {@code stderr} if {@code SYNCSHARE_STATS}
 * is set, and the raw counters are written as JSON to the file {@code SYNCSHARE_STATS_JSON} names.
 */
final class VersioningStats {

    private static final boolean TEXT = System.getenv("SYNCSHARE_STATS") != null;
    private static final String JSON_PATH = System.getenv("SYNCSHARE_STATS_JSON");
    static final boolean ENABLED = TEXT || JSON_PATH != null;

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
    private static Map<String, Long> syncStateCounts() {
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
        Map<String, Long> sync = new LinkedHashMap<>();
        counts.forEach((kind, n) -> {
            String name = kind.name().toLowerCase();
            sync.put(name + ".live", (long) n[0]);
            sync.put(name + ".holding", (long) n[1]);
            sync.put(name + ".distinct", (long) held.getOrDefault(kind, Set.of()).size());
        });
        sync.put("pinnedCommits", (long) pinned.size());
        return sync;
    }

    // The vector clocks the live sync states hold (branching with clocks): how many, their entries in
    // total, and the widest. Each entry is an int, so entries * 4 bytes is their payload. Run after
    // syncStateCounts, whose GC has cleared the states of dead objects.
    private static Map<String, Long> clockCounts() {
        long clocks = 0;
        long entries = 0;
        long widest = 0;
        synchronized (syncStates) {
            for (SyncState s : syncStates) {
                VectorClock clock = s.clock;
                if (clock != null) {
                    clocks++;
                    entries += clock.width();
                    widest = Math.max(widest, clock.width());
                }
            }
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        if (clocks > 0) {
            counts.put("live", clocks);
            counts.put("entries", entries);
            counts.put("widest", widest);
        }
        return counts;
    }

    /** Commit nodes ever created: every commit that did not fold, plus every merge commit. */
    private static long nodesCreated() {
        return commits.get() - folds.get() + merges.get();
    }

    private static Map<String, Long> section(Map<String, Map<String, Long>> sections, String name) {
        return sections.computeIfAbsent(name, n -> new LinkedHashMap<>());
    }

    private static void dump() {
        // The raw counters by section, in print order: both the text summary and the JSON are printed
        // from this, so they cannot drift apart
        Map<String, Map<String, Long>> sections = new LinkedHashMap<>();
        // Values derived from the counters: text only, so averages over runs are taken of the counters
        Map<String, Map<String, Long>> derived = new HashMap<>();

        if (commits.get() > 0 || merges.get() > 0 || folds.get() > 0 || cleanerRegistrations.get() > 0) {
            Map<String, Long> c = section(sections, "commits");
            c.put("commits", commits.get());
            c.put("committedChanges", committedChanges.get());
            c.put("committedArrays", committedArrays.get());
            c.put("committedArrayElements", committedArrayElements.get());
            c.put("merges", merges.get());
            c.put("folds", folds.get());

            Map<String, Long> n = section(sections, "nodes");
            n.put("created", nodesCreated());
            n.put("absorbed", absorbed.get());
            n.put("released", released.get());
            section(derived, "nodes").put("live", nodesCreated() - absorbed.get() - released.get());

            Map<String, Long> d = section(sections, "dead");
            d.put("cleanerRegistrations", cleanerRegistrations.get());
            d.put("markedForPurging", markedForPurging.get());
            d.put("purgedFields", purgedFields.get());
            d.put("purgedArrays", purgedArrays.get());
            d.put("purgeRuns", purgeRuns.get());
            d.put("drainedStagedFields", drainedStagedFields.get());
            d.put("drainedArrays", drainedArrays.get());
            d.put("drainedArrayElements", drainedArrayElements.get());

            Map<String, Long> r = section(sections, "reads");
            r.put("walks", readWalks.get());
            r.put("misses", readWalkMisses.get());
            r.put("visited", readWalkVisited.get());
            r.put("missVisited", readWalkMissVisited.get());
            long hitWalks = readWalks.get() - readWalkMisses.get();
            section(derived, "reads").put("avgHitWalk", hitWalks == 0 ? 0 : (readWalkVisited.get() - readWalkMissVisited.get()) / hitWalks);
            section(derived, "reads").put("avgMissWalk", readWalkMisses.get() == 0 ? 0 : readWalkMissVisited.get() / readWalkMisses.get());

            if (pulls.get() > 0) {
                Map<String, Long> p = section(sections, "pulls");
                p.put("pulls", pulls.get());
                p.put("commits", pulledCommits.get());
                section(derived, "pulls").put("avgCommits", pulledCommits.get() / pulls.get());
            }

            Map<String, Long> s = section(sections, "sharing");
            s.put("objects.versioned", versionedObjects.get());
            s.put("objects.eligible", eligibleObjects.get());
            s.put("arrays.versioned", versionedArrays.get());
            s.put("arrays.eligible", eligibleArrays.get());

            Map<String, Long> w = section(sections, "working");
            w.put("fieldHits", workingFieldHits.get());
            w.put("arrayHits", workingArrayHits.get());
            w.put("arrayBuilds", workingArrayBuilds.get());

            sections.put("sync", syncStateCounts());
            Map<String, Long> clocks = clockCounts();
            if (!clocks.isEmpty()) {
                sections.put("clocks", clocks);
            }
        }
        section(sections, "staging").put("peak", peakStaging.get());

        if (TEXT) {
            StringBuilder sb = new StringBuilder("[versioning-stats] ").append(modelName);
            sections.forEach((name, counters) -> {
                sb.append("\n  ").append(String.format("%-9s", name + ":"));
                counters.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
                derived.getOrDefault(name, Map.of()).forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
            });
            System.err.println(sb);
        }
        if (JSON_PATH != null) {
            try {
                Files.writeString(Path.of(JSON_PATH), toJson(sections));
            } catch (IOException e) {
                System.err.println("[versioning-stats] could not write " + JSON_PATH + ": " + e);
            }
        }
    }

    // Keys are our own identifiers and values are numbers, so nothing needs escaping
    private static String toJson(Map<String, Map<String, Long>> sections) {
        StringBuilder sb = new StringBuilder("{\n  \"model\": \"").append(modelName).append('"');
        sections.forEach((name, counters) -> {
            StringJoiner fields = new StringJoiner(", ", "{", "}");
            counters.forEach((k, v) -> fields.add("\"" + k + "\": " + v));
            sb.append(",\n  \"").append(name).append("\": ").append(fields);
        });
        return sb.append("\n}\n").toString();
    }
}
