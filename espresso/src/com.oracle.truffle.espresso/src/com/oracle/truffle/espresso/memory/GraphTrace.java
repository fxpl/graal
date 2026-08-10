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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    private final AtomicLong ffSeq = new AtomicLong();   // unique ids for fast-forward marker nodes
    private final AtomicLong conflictSeq = new AtomicLong();   // unique ids for (non-committing) conflict nodes

    // Per-thread graph head to chain through *after a fast-forward*. On FF, the thread's model head
    // aliases the adopted tip (another cluster), so the thread's next commit/merge would otherwise
    // parent into that foreign tip, scattering its spine. We remember the FF node here and use it as the
    // parent of the thread's next node, so the spine stays connected: prevHead -> ff -> next. Written and
    // read only by the owning thread (fastForward/commit/merge all run on that thread's view).
    private final ConcurrentHashMap<Long, String> pendingFFHead = new ConcurrentHashMap<>();

    private record Node(String id, long cluster, String label, boolean merge, boolean conflicted, boolean ff, List<String> parents) {
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

    /** Branching (dag): a write commit {@code thread:counter}. Parent is the thread's prior node — the
     *  pending FF node if it just fast-forwarded, else the model-supplied {@code parent} (which carries
     *  fork linkage for a thread's first commit). */
    void commit(long thread, long counter, Timestamp parent, Map<VersionedLocation, Object> changes) {
        String p = threadParent(thread, parent);
        List<String> parents = p == null ? List.of() : List.of(p);
        nodes.add(new Node(thread + ":" + counter, thread, formatChanges(changes), false, false, false, parents));
    }

    /** Branching (dag): a merge commit {@code thread:counter} with its two parents. {@code mine} is the
     *  thread's own prior head (or its pending FF node). */
    void merge(long thread, long counter, Timestamp mine, Timestamp other, boolean conflicted) {
        String m = threadParent(thread, mine);
        List<String> parents = new ArrayList<>(2);
        if (m != null) {
            parents.add(m);
        }
        if (other != null) {
            parents.add(other.toString());
        }
        nodes.add(new Node(thread + ":" + counter, thread, "", true, conflicted, false, parents));
    }

    /** Branching: a fast-forward — a thread adopts another branch's tip {@code to} with no merge commit
     *  (git-style FF, when it had no divergent writes). Rendered as a dashed node on the thread's OWN
     *  spine ({@code prevHead -> ff -> to}) so the adoption is visible and the thread's later commits
     *  chain through it, instead of the model head silently aliasing the other cluster's tip. */
    void fastForward(long thread, Timestamp from, Timestamp to) {
        String mine = threadParent(thread, from);
        String id = thread + ":ff" + ffSeq.incrementAndGet();
        List<String> parents = new ArrayList<>(2);
        if (mine != null) {
            parents.add(mine);
        }
        parents.add(to.toString());
        nodes.add(new Node(id, thread, "fast-forward → " + to, false, false, true, parents));
        pendingFFHead.put(thread, id);   // the thread's next node chains through this FF node
    }

    /** The graph parent to use for {@code thread}'s next node: its pending FF node (consumed) if it just
     *  fast-forwarded, else the model-supplied head. */
    private String threadParent(long thread, Timestamp modelHead) {
        String ff = pendingFFHead.remove(thread);
        if (ff != null) {
            return ff;
        }
        return modelHead == null ? null : modelHead.toString();
    }

    /** Linear: a global-history push at {@code counter}, chained to {@code counter-1}. */
    void push(long counter, Map<VersionedLocation, Object> changes) {
        List<String> parents = counter > 1 ? List.of(Long.toString(counter - 1)) : List.of();
        nodes.add(new Node(Long.toString(counter), -1, formatChanges(changes), false, false, false, parents));
    }

    /**
     * Linear: a <em>failed</em> push/pull — a conflict. Renders a distinct red node off the timeline
     * that records what the thread had staged and why it lost: it was rebased at {@code base}, but the
     * offending location {@code loc} had already advanced to an unseen version at {@code competingCounter}
     * (value {@code competingValue}). Edges point to that competing version and to the thread's base, so
     * the figure shows the thread branching off {@code base} and colliding with the newer write. Without
     * this a conflict left no mark on the graph at all.
     */
    void conflict(long base, Map<VersionedLocation, Object> staged, VersionedLocation loc, long competingCounter, Object competingValue) {
        String id = "conflict_" + conflictSeq.incrementAndGet();
        String label = "CONFLICT\\nstaged: " + formatChanges(staged).replace("\\n", ", ")
                        + "\\non " + esc(describe(loc)) + ": base=" + base + " vs g@" + competingCounter + "=" + esc(value(competingValue));
        List<String> parents = new ArrayList<>(2);
        parents.add(Long.toString(competingCounter));   // the write this push lost to
        if (base > 0) {
            parents.add(Long.toString(base));           // where the pushing thread was rebased
        }
        nodes.add(new Node(id, -1, label, false, true, false, parents));
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
        String label;
        if (n.merge()) {
            label = esc(n.id()) + (n.conflicted() ? " (merge - CONFLICT)" : " (merge)");
        } else if (n.ff()) {
            label = esc(n.label());   // "fast-forward -> X"
        } else if (n.conflicted()) {
            label = n.label();   // synthetic conflict node carries its own full descriptive label
        } else {
            label = n.label().isEmpty() ? esc(n.id()) : esc(n.id()) + "\\n" + n.label();
        }
        sb.append("    \"").append(n.id()).append("\" [label=\"").append(label).append('"');
        if (n.merge()) {
            sb.append(", style=filled, fillcolor=").append(n.conflicted() ? "pink" : "lightgray");
        } else if (n.ff()) {
            sb.append(", style=dashed, color=gray40, fontcolor=gray40");
        } else if (n.conflicted()) {
            sb.append(", style=filled, fillcolor=lightpink, color=red");
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
