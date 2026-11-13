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
import java.lang.ref.Cleaner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

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
 * {@code SYNCSHARE_GRAPH} names a file for the live commit graph itself: every commit with its parents
 * and reference count, and every sync state holding one, named by what it belongs to.
 */
final class VersioningStats {

    private static final boolean TEXT = System.getenv("SYNCSHARE_STATS") != null;
    private static final String JSON_PATH = System.getenv("SYNCSHARE_STATS_JSON");
    private static final String GRAPH_PATH = System.getenv("SYNCSHARE_GRAPH");
    static final boolean ENABLED = TEXT || JSON_PATH != null || GRAPH_PATH != null;

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
    static final AtomicLong compressions = new AtomicLong();    // seen-set compression runs
    static final AtomicLong compressWaitNanos = new AtomicLong(); // ...time waiting for the graph's write side, summed
    static final AtomicLong compressHeldNanos = new AtomicLong(); // ...time holding it, every other graph user stopped, summed
    static final AtomicLong absorbRuns = new AtomicLong();      // ref-count absorptions that took the ABSORB lock
    static final AtomicLong absorbWaitNanos = new AtomicLong(); // ...time waiting for it, summed
    static final AtomicLong absorbHeldNanos = new AtomicLong(); // ...time holding it, summed
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
    static final AtomicLong sharedObjects = new AtomicLong();    // objects (not arrays) marked shared by a store or a thread start
    static final AtomicLong sharedArrays = new AtomicLong();     // arrays marked shared (objects built before versioning, statics and class mirrors are not counted)
    static final AtomicLong versionedObjects = new AtomicLong(); // objects (not arrays) marked versioned, the program's data
    static final AtomicLong versionedArrays = new AtomicLong();  // arrays marked versioned
    static final AtomicLong allocatedObjects = new AtomicLong(); // objects (not arrays) allocated while versioning is on
    static final AtomicLong allocatedArrays = new AtomicLong();  // arrays allocated while versioning is on
    static final AtomicLong workingFieldHits = new AtomicLong(); // field reads answered by a view's working value (no walk)
    static final AtomicLong workingArrayHits = new AtomicLong(); // array accesses that found the view's working copy
    static final AtomicLong workingArrayBuilds = new AtomicLong(); // ...and that had to build it from the history

    // Linear.
    static final AtomicLong pulls = new AtomicLong();           // pulls that moved the base to a newer head
    static final AtomicLong pulledCommits = new AtomicLong();   // ...commits they walked and installed, summed

    // Branching: every merge, by how it ended, and the cost of the walks that coloured the two histories.
    enum MergeOutcome {
        ADOPTED,      // no head of our own: took theirs, no walk
        NOTHING,      // they had no head, no walk
        SAME,         // the same head, no walk
        EPOCH_INCLUDED, // one thread made both heads, theirs first: already ours, no walk
        INCLUDED,     // walked: theirs was already in our history
        CONFLICT,     // walked: both sides wrote a location
        FAST_FORWARD, // walked: ours was in their history, so we took their head
        MERGED        // walked: a merge commit was made
    }
    private static final AtomicLongArray mergeOutcomes = new AtomicLongArray(MergeOutcome.values().length);
    static final AtomicLong mergeVisited = new AtomicLong();    // commits coloured by merge walks, summed
    static final AtomicLong mergeMineOnly = new AtomicLong();   // ...of which only in our history
    static final AtomicLong mergeTheirsOnly = new AtomicLong(); // ...of which only in theirs (the rest are in both)
    static final AtomicLong peakMergeVisited = new AtomicLong(); // most commits coloured by one walk
    static final AtomicLong mergeWalkNanos = new AtomicLong();  // time in the colouring walks, summed
    static final AtomicLong mergeNanos = new AtomicLong();      // time in walked merges overall (walk, conflict check, install)
    // The same walk costs, by outcome, so included walks and real merges can be told apart
    private static final AtomicLongArray mergeVisitedBy = new AtomicLongArray(MergeOutcome.values().length);
    private static final AtomicLongArray mergeMineOnlyBy = new AtomicLongArray(MergeOutcome.values().length);
    private static final AtomicLongArray mergeTheirsOnlyBy = new AtomicLongArray(MergeOutcome.values().length);
    private static final AtomicLongArray mergePeakVisitedBy = new AtomicLongArray(MergeOutcome.values().length);
    private static final AtomicLongArray mergeWalkNanosBy = new AtomicLongArray(MergeOutcome.values().length);

    // Both.
    static final AtomicLong peakStaging = new AtomicLong();     // largest staging map seen (residency)

    // Every sync state still reachable, so the dump can count them by kind and the commits they pin, with
    // what it belongs to (null until described: linear's global state never is). Weak: a sync state whose
    // object died drops out on its own. The descriptions are strings, so they keep no owner alive.
    private static final Map<SyncState, String> syncStates = Collections.synchronizedMap(new WeakHashMap<>());

    // Which thread made each commit node, and how many commits that thread had made by then ("main #12"),
    // for the graph dump. Weak, like syncStates: a released commit drops out.
    private static final Map<Commit, String> commitsMade = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<int[]> threadCommits = ThreadLocal.withInitial(() -> new int[1]);

    private static final AtomicBoolean ARMED = new AtomicBoolean();
    private static volatile String modelName = ""; // the model that armed the dump, for its heading
    private static volatile Cleaner cleaner;        // the model's, which releases dead owners' sync states

    private VersioningStats() {
    }

    /** Register the shutdown dump once (called by a versioned model's constructor). No-op when disabled. */
    static void arm(String model, Cleaner modelCleaner) {
        if (ENABLED && ARMED.compareAndSet(false, true)) {
            modelName = model;
            cleaner = modelCleaner;
            Runtime.getRuntime().addShutdownHook(new Thread(VersioningStats::dump, "versioning-stats-dump"));
        }
    }

    static void onCommit(int fieldChanges, int arrays, long arrayElements) {
        if (ENABLED) {
            threadCommits.get()[0]++;
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

    /** A seen-set compression run, from asking for the graph's write side, through taking it, to letting it go. */
    static void onCompress(long asked, long took, long released) {
        if (ENABLED) {
            compressions.incrementAndGet();
            compressWaitNanos.addAndGet(took - asked);
            compressHeldNanos.addAndGet(released - took);
        }
    }

    /** A ref-count absorption, from asking for the ABSORB lock, through taking it, to letting it go. */
    static void onAbsorbRun(long asked, long took, long released) {
        if (ENABLED) {
            absorbRuns.incrementAndGet();
            absorbWaitNanos.addAndGet(took - asked);
            absorbHeldNanos.addAndGet(released - took);
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
            syncStates.put(state, null);
        }
    }

    /** {@code state} is a lock or volatile state of {@code owner}: named by its class and identity. */
    static void describeObject(SyncState state, StaticObject owner) {
        if (ENABLED) {
            syncStates.put(state, owner.getKlass().getExternalName() + "@" + Integer.toHexString(System.identityHashCode(owner)));
        }
    }

    /** {@code state} is {@code field}'s volatile state on the object {@code lock} is the lock state of. */
    static void describeField(SyncState state, SyncState lock, Field field) {
        if (ENABLED) {
            syncStates.put(state, syncStates.get(lock) + "." + field.getNameAsString());
        }
    }

    /** {@code state} is {@code klass}'s initialisation state. */
    static void describeClass(SyncState state, Klass klass) {
        if (ENABLED) {
            syncStates.put(state, klass.getExternalName());
        }
    }

    /**
     * {@code state} is {@code thread}'s own state: named by the thread's name when its view was bound.
     * The name is read from its raw slot: {@code Thread.name} is volatile, and a modelled read would pull,
     * binding this same state again.
     */
    static void describeThread(SyncState state, StaticObject thread) {
        if (ENABLED) {
            Meta meta = thread.getKlass().getMeta();
            syncStates.put(state, meta.toHostString((StaticObject) meta.java_lang_Thread_name.getRawValue(thread)));
        }
    }

    /**
     * {@code commit} is a new node made by the thread whose state is {@code thread}: labelled with the
     * thread's name and its commit count, "main #12", or "main #12 merge" for a merge made after it.
     */
    static void describeCommit(Commit commit, SyncState thread, boolean merge) {
        if (ENABLED) {
            commitsMade.put(commit, syncStates.get(thread) + " #" + threadCommits.get()[0] + (merge ? " merge" : ""));
        }
    }

    static void onShared(boolean array) {
        if (ENABLED) {
            (array ? sharedArrays : sharedObjects).incrementAndGet();
        }
    }

    static void onVersioned(boolean array) {
        if (ENABLED) {
            (array ? versionedArrays : versionedObjects).incrementAndGet();
        }
    }

    static void onAllocated(boolean array) {
        if (ENABLED) {
            (array ? allocatedArrays : allocatedObjects).incrementAndGet();
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

    /** A merge that ended without walking the histories. */
    static void onMergeUnwalked(MergeOutcome outcome) {
        if (ENABLED) {
            mergeOutcomes.incrementAndGet(outcome.ordinal());
        }
    }

    /**
     * A merge that walked: {@code visited} commits coloured, {@code mineOnly} and {@code theirsOnly} of them
     * exclusive to one side, {@code walkNanos} in the walk and {@code nanos} in the merge overall.
     */
    static void onMergeWalked(MergeOutcome outcome, int visited, int mineOnly, int theirsOnly, long walkNanos, long nanos) {
        if (ENABLED) {
            mergeOutcomes.incrementAndGet(outcome.ordinal());
            mergeVisited.addAndGet(visited);
            mergeMineOnly.addAndGet(mineOnly);
            mergeTheirsOnly.addAndGet(theirsOnly);
            observe(peakMergeVisited, visited);
            mergeWalkNanos.addAndGet(walkNanos);
            mergeNanos.addAndGet(nanos);

            int o = outcome.ordinal();
            mergeVisitedBy.addAndGet(o, visited);
            mergeMineOnlyBy.addAndGet(o, mineOnly);
            mergeTheirsOnlyBy.addAndGet(o, theirsOnly);
            mergeWalkNanosBy.addAndGet(o, walkNanos);
            long cur;
            while (visited > (cur = mergePeakVisitedBy.get(o)) && !mergePeakVisitedBy.compareAndSet(o, cur, visited)) {
                // another thread raised the peak meanwhile: retry against it
            }
        }
    }

    /** A clock reading for timing merges, or 0 when the stats are off so the call costs nothing. */
    static long now() {
        return ENABLED ? System.nanoTime() : 0;
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

    // Collect the dead objects and let the model's Cleaner release their sync states' commits, so every
    // reference left on a commit belongs to something still alive. The Cleaner's queue has no order, so
    // a sentinel's action running only says that collection's actions are under way: a few rounds settle it.
    private static void quiesce() {
        for (int round = 0; round < 3; round++) {
            CountDownLatch ran = new CountDownLatch(1);
            if (cleaner != null) {
                cleaner.register(new Object(), ran::countDown);
            }
            System.gc();
            try {
                ran.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // For each kind of sync state still alive: how many, how many hold a commit, and how many distinct
    // commits those are; then the distinct commits pinned across all kinds.
    private static Map<String, Long> syncStateCounts() {
        // clear sync states of objects that have died, so "live" counts only those still reachable
        quiesce();
        Map<SyncState.Kind, int[]> counts = new EnumMap<>(SyncState.Kind.class);
        Map<SyncState.Kind, Set<Commit>> held = new EnumMap<>(SyncState.Kind.class);
        Set<Commit> pinned = new HashSet<>();
        synchronized (syncStates) {
            for (SyncState s : syncStates.keySet()) {
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

    /**
     * The commit graph still reachable from a live sync state, as compression has left it: each commit's
     * depth (one more than its deepest parent's), the sync states holding it, and how many reachable
     * commits have it as a parent. Those two are every reference a commit should have, so its ref count
     * less them is a reference nobody can account for (or, below zero, one released twice).
     */
    private record LiveGraph(Map<Commit, Integer> depth, Map<Commit, List<SyncState>> holders, Map<Commit, Integer> children) {

        static LiveGraph walk() {
            Map<Commit, Integer> depth = new HashMap<>();
            Map<Commit, List<SyncState>> holders = new HashMap<>();
            synchronized (syncStates) {
                for (SyncState s : syncStates.keySet()) {
                    Commit c = s.commit;
                    if (c != null) {
                        measureDepth(c, depth);
                        holders.computeIfAbsent(c, k -> new ArrayList<>()).add(s);
                    }
                }
            }
            Map<Commit, Integer> children = new HashMap<>();
            for (Commit c : depth.keySet()) {
                for (Commit p : c.parents()) {
                    children.merge(p, 1, Integer::sum);
                }
            }
            return new LiveGraph(depth, holders, children);
        }

        int unexplained(Commit c) {
            return c.getRefCount() - holders.getOrDefault(c, List.of()).size() - children.getOrDefault(c, 0);
        }
    }

    // The live graph's shape: how many commits, its depth (the longest path from a commit to a root) and
    // its width (the most commits at one depth; 1 for a single chain). Also the deepest generation among
    // them, the length that path had when the commits were created, and the references no holder
    // accounts for (missing) or that holders account for twice over (extra). Run after syncStateCounts,
    // which lets the Cleaner release dead states.
    private static Map<String, Long> liveGraph(LiveGraph live) {
        Map<Integer, Integer> atDepth = new HashMap<>();
        long deepest = 0;
        long generation = 0;
        long missing = 0;
        long extra = 0;
        for (Map.Entry<Commit, Integer> e : live.depth().entrySet()) {
            atDepth.merge(e.getValue(), 1, Integer::sum);
            deepest = Math.max(deepest, e.getValue());
            generation = Math.max(generation, e.getKey().generation());
            int unexplained = live.unexplained(e.getKey());
            missing += Math.max(unexplained, 0);
            extra += Math.max(-unexplained, 0);
        }
        Map<String, Long> graph = new LinkedHashMap<>();
        graph.put("reachable", (long) live.depth().size());
        graph.put("depth", deepest);
        graph.put("width", (long) atDepth.values().stream().mapToInt(Integer::intValue).max().orElse(0));
        graph.put("generation", generation);
        graph.put("missingRefs", missing);
        graph.put("extraRefs", extra);
        return graph;
    }

    // The live graph for SYNCSHARE_GRAPH: every commit, numbered, with its parents, ref count and what
    // holds it, each holder as its kind and owner. Owners are names from the guest, so they are escaped.
    private static String graphJson(LiveGraph live) {
        Map<Commit, Integer> ids = new HashMap<>();
        live.depth().keySet().forEach(c -> ids.put(c, ids.size()));
        StringBuilder sb = new StringBuilder("{\n  \"model\": \"").append(modelName).append("\",\n  \"commits\": [");
        StringJoiner commitsOut = new StringJoiner(",\n    ", "\n    ", "\n  ");
        ids.forEach((c, id) -> {
            StringJoiner parents = new StringJoiner(", ", "[", "]");
            c.parents().forEach(p -> parents.add(String.valueOf(ids.get(p))));
            StringJoiner holders = new StringJoiner(", ", "[", "]");
            for (SyncState s : live.holders().getOrDefault(c, List.of())) {
                String owner = syncStates.get(s);
                holders.add("{\"kind\": \"" + s.kind.name().toLowerCase() + "\", \"owner\": " + (owner == null ? "null" : quote(owner)) + "}");
            }
            String made = commitsMade.get(c);
            commitsOut.add("{\"id\": " + id + ", \"made\": " + (made == null ? "null" : quote(made)) +
                            ", \"generation\": " + c.generation() + ", \"depth\": " + live.depth().get(c) +
                            ", \"refs\": " + c.getRefCount() + ", \"children\": " + live.children().getOrDefault(c, 0) +
                            ", \"unexplained\": " + live.unexplained(c) + ", \"changed\": " + c.changes().size() +
                            ", \"parents\": " + parents + ", \"holders\": " + holders + "}");
        });
        return sb.append(ids.isEmpty() ? "" : commitsOut.toString()).append("]\n}\n").toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') {
                sb.append('\\').append(ch);
            } else if (ch < 0x20) {
                sb.append(String.format("\\u%04x", (int) ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.append('"').toString();
    }

    // Each commit's depth, one more than its deepest parent's (a root's is 1), into depth: a post-order
    // walk with an explicit stack, as a recursive one would overflow on a long chain
    private static void measureDepth(Commit from, Map<Commit, Integer> depth) {
        Deque<Commit> pending = new ArrayDeque<>();
        pending.push(from);
        while (!pending.isEmpty()) {
            Commit c = pending.peek();
            if (depth.containsKey(c)) {
                pending.pop();
                continue;
            }
            boolean parentsMeasured = true;
            for (Commit p : c.parents()) {
                if (!depth.containsKey(p)) {
                    pending.push(p);
                    parentsMeasured = false;
                }
            }
            if (parentsMeasured) {
                pending.pop();
                int d = 1;
                for (Commit p : c.parents()) {
                    d = Math.max(d, depth.get(p) + 1);
                }
                depth.put(c, d);
            }
        }
    }

    /** The walk costs of the merges that ended as {@code outcome}, as {@code <key>Visited} and so on. */
    private static void walkedByOutcome(Map<String, Long> m, MergeOutcome outcome, String key) {
        int o = outcome.ordinal();
        m.put(key + "Visited", mergeVisitedBy.get(o));
        m.put(key + "MineOnly", mergeMineOnlyBy.get(o));
        m.put(key + "TheirsOnly", mergeTheirsOnlyBy.get(o));
        m.put(key + "PeakVisited", mergePeakVisitedBy.get(o));
        m.put(key + "WalkNanos", mergeWalkNanosBy.get(o));
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

            Map<String, Long> rw = section(sections, "rewrite");
            rw.put("compressions", compressions.get());
            rw.put("compressWaitNanos", compressWaitNanos.get());
            rw.put("compressHeldNanos", compressHeldNanos.get());
            rw.put("absorbRuns", absorbRuns.get());
            rw.put("absorbWaitNanos", absorbWaitNanos.get());
            rw.put("absorbHeldNanos", absorbHeldNanos.get());

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

            long walkedMerges = 0;
            long allMerges = 0;
            for (MergeOutcome o : MergeOutcome.values()) {
                long count = mergeOutcomes.get(o.ordinal());
                allMerges += count;
                if (o.ordinal() >= MergeOutcome.INCLUDED.ordinal()) {
                    walkedMerges += count;
                }
            }
            if (allMerges > 0) {
                Map<String, Long> m = section(sections, "merge");
                m.put("adopted", mergeOutcomes.get(MergeOutcome.ADOPTED.ordinal()));
                m.put("nothing", mergeOutcomes.get(MergeOutcome.NOTHING.ordinal()));
                m.put("same", mergeOutcomes.get(MergeOutcome.SAME.ordinal()));
                m.put("epochIncluded", mergeOutcomes.get(MergeOutcome.EPOCH_INCLUDED.ordinal()));
                m.put("included", mergeOutcomes.get(MergeOutcome.INCLUDED.ordinal()));
                m.put("conflict", mergeOutcomes.get(MergeOutcome.CONFLICT.ordinal()));
                m.put("fastForward", mergeOutcomes.get(MergeOutcome.FAST_FORWARD.ordinal()));
                m.put("merged", mergeOutcomes.get(MergeOutcome.MERGED.ordinal()));
                m.put("visited", mergeVisited.get());
                m.put("mineOnly", mergeMineOnly.get());
                m.put("theirsOnly", mergeTheirsOnly.get());
                m.put("peakVisited", peakMergeVisited.get());
                m.put("walkNanos", mergeWalkNanos.get());
                m.put("nanos", mergeNanos.get());
                walkedByOutcome(m, MergeOutcome.INCLUDED, "included");
                walkedByOutcome(m, MergeOutcome.FAST_FORWARD, "fastForward");
                walkedByOutcome(m, MergeOutcome.MERGED, "merged");
                walkedByOutcome(m, MergeOutcome.CONFLICT, "conflict");
                if (walkedMerges > 0) {
                    section(derived, "merge").put("avgVisited", mergeVisited.get() / walkedMerges);
                    section(derived, "merge").put("avgShared",
                                    (mergeVisited.get() - mergeMineOnly.get() - mergeTheirsOnly.get()) / walkedMerges);
                }
            }

            Map<String, Long> s = section(sections, "sharing");
            s.put("objects.shared", sharedObjects.get());
            s.put("objects.versioned", versionedObjects.get());
            s.put("arrays.shared", sharedArrays.get());
            s.put("arrays.versioned", versionedArrays.get());
            s.put("objects.allocated", allocatedObjects.get());
            s.put("arrays.allocated", allocatedArrays.get());

            Map<String, Long> w = section(sections, "working");
            w.put("fieldHits", workingFieldHits.get());
            w.put("arrayHits", workingArrayHits.get());
            w.put("arrayBuilds", workingArrayBuilds.get());

            sections.put("sync", syncStateCounts());
            LiveGraph live = LiveGraph.walk();
            sections.put("graph", liveGraph(live));
            if (GRAPH_PATH != null) {
                try {
                    Files.writeString(Path.of(GRAPH_PATH), graphJson(live));
                } catch (IOException e) {
                    System.err.println("[versioning-stats] could not write " + GRAPH_PATH + ": " + e);
                }
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
