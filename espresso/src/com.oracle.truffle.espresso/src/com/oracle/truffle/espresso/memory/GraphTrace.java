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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records the memory model's information-flow graph as a <em>stream of events</em>, emitted by the
 * active model as commits / merges / pushes happen, and renders it to Graphviz {@code .dot} at JVM
 * shutdown. Enabled by {@code --java.MemoryModelGraph=<path>}.
 *
 * <p><b>Why a stream, not a walk.</b> The obvious alternative — reconstruct the graph from the live
 * in-memory structure at the end — is wrong the moment that structure is compressed: history
 * collapsing ({@code --java.MemoryModelCollapse}) reclaims versions. Recording each node as it is created, into this independent buffer, is correct
 * regardless: the event is captured before anything can reclaim it, and labels are formatted while the
 * klasses/values are still live. (The buffer grows with history; for the small programs this targets
 * — figures, debugging — that is fine. For very long runs it could stream to disk behind the same
 * {@code commit}/{@code push} calls without changing callers.)
 *
 * <p><b>Two shapes.</b> {@code branching} emits a commit DAG — {@link #commit} write nodes and
 * {@link #merge} merge nodes, clustered per thread, edges to parents (gitmem's {@code revision_graph}).
 * {@code linear} emits the single global-history timeline — {@link #push} nodes in one chain.
 *
 * <p>Thread-safe: guest threads emit concurrently into a {@link ConcurrentLinkedQueue}; rendering runs
 * once, single-threaded, at shutdown.
 */
public final class GraphTrace {

    private final String path;
    private final boolean linear;
    private final Queue<Node> nodes = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean armed = new AtomicBoolean();

    private record Node(String id, long cluster, String label, boolean merge, boolean conflicted, List<String> parents) {
    }

    public GraphTrace(String path, boolean linear) {
        this.path = path;
        this.linear = linear;
    }

    /** Register the shutdown render once (called by the owning model's constructor). */
    void arm() {
        if (armed.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::render, "memory-graph-render"));
        }
    }

    /** Branching (dag): a write commit {@code thread:counter} with the given single parent (or none). */
    void commit(long thread, long counter, Timestamp parent, Map<VersionedLocation, Object> changes) {
        List<String> parents = parent == null ? List.of() : List.of(parent.toString());
        nodes.add(new Node(thread + ":" + counter, thread, formatChanges(changes), false, false, parents));
    }

    /** Branching (dag): a merge commit {@code thread:counter} with its two parents. */
    void merge(long thread, long counter, Timestamp mine, Timestamp other, boolean conflicted) {
        List<String> parents = new ArrayList<>(2);
        if (mine != null) {
            parents.add(mine.toString());
        }
        if (other != null) {
            parents.add(other.toString());
        }
        nodes.add(new Node(thread + ":" + counter, thread, "", true, conflicted, parents));
    }

    /** Linear: a global-history push at {@code counter}, chained to {@code counter-1}. */
    void push(long counter, Map<VersionedLocation, Object> changes) {
        List<String> parents = counter > 1 ? List.of(Long.toString(counter - 1)) : List.of();
        nodes.add(new Node(Long.toString(counter), -1, formatChanges(changes), false, false, parents));
    }

    private void render() {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph MemoryGraph {\n  rankdir=BT;\n  node [shape=box];\n");
        if (linear) {
            renderChain(sb);
        } else {
            renderClustered(sb);
        }
        // Edges (shared): each node to each of its parents.
        for (Node n : nodes) {
            for (String p : n.parents()) {
                sb.append("  \"").append(n.id()).append("\" -> \"").append(p).append("\";\n");
            }
        }
        sb.append("}\n");
        try {
            Files.writeString(Path.of(path), sb.toString());
        } catch (IOException e) {
            System.err.println("[memory-graph] failed to write " + path + ": " + e);
        }
    }

    /** Branching: one dashed subgraph cluster per thread, commits ordered by counter. */
    private void renderClustered(StringBuilder sb) {
        TreeMap<Long, List<Node>> byThread = new TreeMap<>();
        for (Node n : nodes) {
            byThread.computeIfAbsent(n.cluster(), k -> new ArrayList<>()).add(n);
        }
        for (Map.Entry<Long, List<Node>> e : byThread.entrySet()) {
            sb.append("  subgraph cluster_").append(e.getKey()).append(" {\n");
            sb.append("    label=\"Thread ").append(e.getKey()).append("\";\n    style=dashed;\n");
            for (Node n : e.getValue()) {
                emitNode(sb, n);
            }
            sb.append("  }\n");
        }
    }

    /** Linear: the global history as a single chain (no clusters). */
    private void renderChain(StringBuilder sb) {
        for (Node n : nodes) {
            emitNode(sb, n);
        }
    }

    private void emitNode(StringBuilder sb, Node n) {
        String label = n.merge() ? esc(n.id()) + (n.conflicted() ? " (merge - CONFLICT)" : " (merge)")
                        : (n.label().isEmpty() ? esc(n.id()) : esc(n.id()) + "\\n" + n.label());
        sb.append("    \"").append(n.id()).append("\" [label=\"").append(label).append('"');
        if (n.merge()) {
            sb.append(", style=filled, fillcolor=").append(n.conflicted() ? "pink" : "lightgray");
        }
        sb.append("];\n");
    }

    private static String formatChanges(Map<VersionedLocation, Object> changes) {
        StringBuilder b = new StringBuilder();
        boolean first = true;
        for (Map.Entry<VersionedLocation, Object> w : changes.entrySet()) {
            if (!first) {
                b.append("\\n");
            }
            b.append(esc(describe(w.getKey()))).append('→').append(esc(value(w.getValue())));
            first = false;
        }
        return b.toString();
    }

    private static String describe(VersionedLocation loc) {
        if (loc instanceof FieldLocation f) {
            return f.field().getDeclaringKlass().getName() + "." + f.field().getName();
        } else if (loc instanceof ArrayLocation a) {
            return a.array().getKlass().getName() + "[" + a.index() + "]";
        }
        return String.valueOf(loc);
    }

    private static String value(Object v) {
        String s = String.valueOf(v);
        return s.length() > 24 ? s.substring(0, 24) + "..." : s;
    }

    /** Escape for a double-quoted Graphviz label; \\n is left literal (a line break in the label). */
    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
