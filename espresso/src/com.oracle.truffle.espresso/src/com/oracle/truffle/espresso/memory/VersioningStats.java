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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * The state a versioned model leaves at the end of a run, reported once as the context closes. What
 * happens during the run is reported as it happens, by {@link ModelEvents}.
 *
 * <p>The report walks the commit graph still reachable from a live sync state. It runs only when a
 * recording enables {@link ModelEvents.SyncStates} or {@link ModelEvents.LiveGraph}, or when
 * {@code SYNCSHARE_GRAPH} names a file for the graph itself (every commit with its parents and reference
 * count, and every sync state holding one, named by what it belongs to). The walk also checks that no
 * commit has fewer references than the sync states and children holding it, and fails the run if one does.
 */
final class VersioningStats {

    private static final String GRAPH_PATH = System.getenv("SYNCSHARE_GRAPH");
    // the names are only for the graph dump
    private static final boolean NAMING = GRAPH_PATH != null;

    // What each sync state belongs to, null until described (linear's global state never is). Weak, so a
    // sync state whose object died drops out on its own. The names are strings, so they keep no owner alive.
    private static final Map<SyncState<?>, String> names = Collections.synchronizedMap(new WeakHashMap<>());

    // Which thread made each commit node, and how many commits that thread had made by then ("main #12").
    // Weak, like names, so a released commit drops out.
    private static final Map<Commit, String> commitsMade = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<int[]> threadCommits = ThreadLocal.withInitial(() -> new int[1]);

    private VersioningStats() {
    }

    /** The calling thread committed, so its next commit node is labelled one further on. */
    static void countCommit() {
        if (NAMING) {
            threadCommits.get()[0]++;
        }
    }

    /** {@code state} is a lock or volatile state of {@code owner}, named by its class and identity. */
    static void describeObject(SyncState<?> state, StaticObject owner) {
        if (NAMING) {
            names.put(state, owner.getKlass().getExternalName() + "@" + Integer.toHexString(System.identityHashCode(owner)));
        }
    }

    /** {@code state} is {@code field}'s volatile state on the object {@code lock} is the lock state of. */
    static void describeField(SyncState<?> state, SyncState<?> lock, Field field) {
        if (NAMING) {
            names.put(state, names.get(lock) + "." + field.getNameAsString());
        }
    }

    /** {@code state} is {@code klass}'s initialisation state. */
    static void describeClass(SyncState<?> state, Klass klass) {
        if (NAMING) {
            names.put(state, klass.getExternalName());
        }
    }

    /**
     * {@code state} is {@code thread}'s own state, named by the thread's name when its view was bound.
     * The name is read from its raw slot. {@code Thread.name} is volatile, and a modelled read would pull,
     * binding this same state again.
     */
    static void describeThread(SyncState<?> state, StaticObject thread) {
        if (NAMING) {
            Meta meta = thread.getKlass().getMeta();
            names.put(state, meta.toHostString((StaticObject) meta.java_lang_Thread_name.getRawValue(thread)));
        }
    }

    /**
     * {@code commit} is a new node made by the thread whose state is {@code thread}, labelled with the
     * thread's name and its commit count, "main #12", or "main #12 merge" for a merge made after it.
     */
    static void describeCommit(Commit commit, SyncState<?> thread, boolean merge) {
        if (NAMING) {
            commitsMade.put(commit, names.get(thread) + " #" + threadCommits.get()[0] + (merge ? " merge" : ""));
        }
    }

    /**
     * Report the state {@code model} leaves, once, as the context closes and after every guest thread
     * has ended. {@code cleaner} is the model's, which releases dead owners' sync states.
     */
    static void report(String model, Cleaner cleaner) {
        boolean isRecorded = new ModelEvents.SyncStates().isEnabled() || new ModelEvents.LiveGraph().isEnabled();
        if (!isRecorded && !NAMING) {
            return;
        }
        // release the sync states of objects that have died, so only those still reachable are counted
        quiesce(cleaner);
        emitSyncStates();
        Reachable reachable = Reachable.walk();
        emitLiveGraph(reachable);
        if (NAMING) {
            try {
                Files.writeString(Path.of(GRAPH_PATH), graphJson(model, reachable));
            } catch (IOException e) {
                System.err.println("[versioning-stats] could not write " + GRAPH_PATH + ": " + e);
            }
        }
        long extra = reachable.extraRefs();
        if (extra > 0) {
            throw new IllegalStateException("Sync-as-Share: " + extra + " commit references released more often than taken");
        }
    }

    // Collect the dead objects and let the model's Cleaner release their sync states' commits, so every
    // reference left on a commit belongs to something still alive. The Cleaner's queue has no order, so
    // a sentinel's action running only says that collection's actions are under way. A few rounds settle it.
    private static void quiesce(Cleaner cleaner) {
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

    // For each kind of sync state still alive, how many, how many hold a commit, and how many distinct
    // commits those are
    private static void emitSyncStates() {
        Map<SyncState.Kind, long[]> counts = new EnumMap<>(SyncState.Kind.class);
        Map<SyncState.Kind, Set<Commit>> held = new EnumMap<>(SyncState.Kind.class);
        synchronized (CommitSyncState.ALL) {
            for (CommitSyncState s : CommitSyncState.ALL) {
                long[] n = counts.computeIfAbsent(s.kind, k -> new long[2]);
                n[0]++;
                Commit c = s.get();
                if (c != null) {
                    n[1]++;
                    held.computeIfAbsent(s.kind, k -> new HashSet<>()).add(c);
                }
            }
        }
        counts.forEach((kind, n) -> {
            ModelEvents.SyncStates e = new ModelEvents.SyncStates();
            if (e.isEnabled()) {
                e.kind = kind.name();
                e.live = n[0];
                e.holding = n[1];
                e.distinct = held.getOrDefault(kind, Set.of()).size();
                e.commit();
            }
        });
    }

    // The live graph's shape, how many commits, its depth (the longest path from a commit to a root) and
    // its width (the most commits at one depth, 1 for a single chain), the deepest generation among them,
    // the commits a sync state holds, and the references no holder accounts for
    private static void emitLiveGraph(Reachable reachable) {
        ModelEvents.LiveGraph e = new ModelEvents.LiveGraph();
        if (!e.isEnabled()) {
            return;
        }
        Map<Integer, Integer> atDepth = new HashMap<>();
        long deepest = 0;
        long generation = 0;
        for (Map.Entry<Commit, Integer> d : reachable.depth().entrySet()) {
            atDepth.merge(d.getValue(), 1, Integer::sum);
            deepest = Math.max(deepest, d.getValue());
            generation = Math.max(generation, d.getKey().generation());
        }
        e.reachable = reachable.depth().size();
        e.depth = deepest;
        e.width = atDepth.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        e.generation = generation;
        e.pinnedCommits = reachable.holders().size();
        e.missingRefs = reachable.missingRefs();
        e.commit();
    }

    /**
     * The commit graph still reachable from a live sync state, as compression has left it, with each
     * commit's depth (one more than its deepest parent's), the sync states holding it, and how many
     * reachable commits have it as a parent. Those two are every reference a commit should have, so its
     * ref count less them is a reference nobody can account for (or, below zero, one released twice).
     */
    private record Reachable(Map<Commit, Integer> depth, Map<Commit, List<CommitSyncState>> holders, Map<Commit, Integer> children) {

        static Reachable walk() {
            Map<Commit, Integer> depth = new HashMap<>();
            Map<Commit, List<CommitSyncState>> holders = new HashMap<>();
            synchronized (CommitSyncState.ALL) {
                for (CommitSyncState s : CommitSyncState.ALL) {
                    Commit c = s.get();
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
            return new Reachable(depth, holders, children);
        }

        int unexplained(Commit c) {
            return c.getRefCount() - holders.getOrDefault(c, List.of()).size() - children.getOrDefault(c, 0);
        }

        long missingRefs() {
            return depth.keySet().stream().mapToLong(c -> Math.max(unexplained(c), 0)).sum();
        }

        long extraRefs() {
            return depth.keySet().stream().mapToLong(c -> Math.max(-unexplained(c), 0)).sum();
        }
    }

    // The live graph for SYNCSHARE_GRAPH, every commit, numbered, with its parents, ref count and what
    // holds it, each holder as its kind and owner. Owners are names from the guest, so they are escaped.
    private static String graphJson(String model, Reachable reachable) {
        Map<Commit, Integer> ids = new HashMap<>();
        reachable.depth().keySet().forEach(c -> ids.put(c, ids.size()));
        StringBuilder sb = new StringBuilder("{\n  \"model\": \"").append(model).append("\",\n  \"commits\": [");
        StringJoiner commitsOut = new StringJoiner(",\n    ", "\n    ", "\n  ");
        ids.forEach((c, id) -> {
            StringJoiner parents = new StringJoiner(", ", "[", "]");
            c.parents().forEach(p -> parents.add(String.valueOf(ids.get(p))));
            StringJoiner holders = new StringJoiner(", ", "[", "]");
            for (CommitSyncState s : reachable.holders().getOrDefault(c, List.of())) {
                String owner = names.get(s);
                holders.add("{\"kind\": \"" + s.kind.name().toLowerCase() + "\", \"owner\": " + (owner == null ? "null" : quote(owner)) + "}");
            }
            String made = commitsMade.get(c);
            commitsOut.add("{\"id\": " + id + ", \"made\": " + (made == null ? "null" : quote(made)) +
                            ", \"generation\": " + c.generation() + ", \"depth\": " + reachable.depth().get(c) +
                            ", \"refs\": " + c.getRefCount() + ", \"children\": " + reachable.children().getOrDefault(c, 0) +
                            ", \"unexplained\": " + reachable.unexplained(c) + ", \"changed\": " + c.changes().size() +
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

    // Each commit's depth, one more than its deepest parent's (a root's is 1), into depth. A post-order
    // walk with an explicit stack, as a recursive one would overflow on a long chain.
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
}
