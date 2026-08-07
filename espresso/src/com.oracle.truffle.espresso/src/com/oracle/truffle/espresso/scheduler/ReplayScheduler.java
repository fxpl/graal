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
package com.oracle.truffle.espresso.scheduler;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * Replays a schedule recorded by {@link RecordingScheduler}: on a later run it forces threads to pass
 * the meaningful seams (the flow handoffs) in the recorded order, so the run is deterministic under
 * isolation (design/schedule-explorer.md §5.4 mode 2).
 *
 * <p><b>Status: verify-only.</b> This slice parses the schedule and checks the run's observed handoff
 * order against it — reporting an exact match or the first divergence — but does <em>not yet block</em>
 * to force the order. Forcing needs one refinement first: a RECEIVE at a monitor enter must gate
 * <em>before</em> the physical acquire, otherwise a thread could win the lock, then block waiting its
 * turn while holding it, and deadlock a thread scheduled earlier. Since the observation seam fires
 * post-acquire, forcing needs a control point ahead of the acquire. Verify-only is deadlock-free (it
 * never blocks) and already useful: it measures how deterministic the natural schedule is and confirms
 * the schedule round-trips. The blocking policy replaces the marked spot below.
 *
 * <p>Selected by {@code --java.ScheduleDrive=replay --java.ScheduleFile=<path>}.
 */
public final class ReplayScheduler extends AbstractDriveScheduler {

    private final PrintStream err = System.err;
    private final List<String> expected;   // recorded handoff lines, in order (kind+thread+op)

    // Mirror of RecordingScheduler's pairing state, to reconstruct the observed handoff sequence.
    private final Map<StaticObject, String> flowPublisher = new ConcurrentHashMap<>();
    private final Map<StaticObject, Integer> flowCount = new ConcurrentHashMap<>();
    private final List<long[]> observedSeq = Collections.synchronizedList(new ArrayList<>());
    private final List<String> observed = Collections.synchronizedList(new ArrayList<>());

    public ReplayScheduler(String path) {
        this.expected = load(path);
    }

    private static List<String> load(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("--java.ScheduleDrive=replay requires --java.ScheduleFile=<path>");
        }
        try {
            List<String> lines = new ArrayList<>();
            for (String line : Files.readAllLines(Path.of(path), StandardCharsets.UTF_8)) {
                String t = line.strip();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    lines.add(t);
                }
            }
            return lines;
        } catch (IOException ex) {
            throw new UncheckedIOException("--java.ScheduleFile: cannot read " + path, ex);
        }
    }

    /** A stable key for a handoff line, ignoring the count (which is informational). */
    private static String key(String kind, String thread, SyncOp op, int subjectId) {
        return kind + " " + thread + " " + op + " S" + subjectId;
    }

    @Override
    protected void onAppSeam(long seq, String thread, SyncOp op, StaticObject subject,
                    boolean hasSubject, int subjectId, int pendingWrites) {
        boolean flow = op.isRelease() && pendingWrites > 0 && hasSubject;
        if (flow) {
            flowPublisher.put(subject, thread);
            flowCount.put(subject, pendingWrites);
            record(seq, key("PUBLISH", thread, op, subjectId));
        }
        if (op.isAcquire() && hasSubject) {
            String publisher = flowPublisher.get(subject);
            if (publisher != null && !publisher.equals(thread)) {
                flowPublisher.remove(subject);
                flowCount.remove(subject);
                record(seq, key("RECEIVE", thread, op, subjectId));
            }
        }
        // TODO(replay-forcing): block here until this handoff is next in `expected` (with the
        // pre-acquire gate refinement noted in the class doc), then notify. Verify-only for now.
    }

    private void record(long seq, String observedKey) {
        observedSeq.add(new long[]{seq});
        observed.add(observedKey);
    }

    @Override
    @TruffleBoundary
    public void report() {
        // Sort the observed handoffs by their global seam sequence to get the run's actual order.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < observed.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> Long.compare(observedSeq.get(a)[0], observedSeq.get(b)[0]));
        List<String> actual = new ArrayList<>();
        for (int i : order) {
            // strip the count for comparison: recorded keys already omit it, actual omits it too
            actual.add(observed.get(i));
        }
        int n = Math.min(actual.size(), expected.size());
        int diverge = -1;
        for (int i = 0; i < n; i++) {
            if (!stripCount(expected.get(i)).equals(actual.get(i))) {
                diverge = i;
                break;
            }
        }
        if (diverge < 0 && actual.size() == expected.size()) {
            err.printf("[replay] verify-only: run matched the recorded schedule (%d handoffs)%n", expected.size());
        } else if (diverge < 0) {
            err.printf("[replay] verify-only: recorded %d handoffs, ran %d — common prefix matched, lengths differ%n",
                            expected.size(), actual.size());
        } else {
            err.printf("[replay] verify-only: DIVERGED at handoff %d: expected [%s], ran [%s]%n",
                            diverge, stripCount(expected.get(diverge)), actual.get(diverge));
        }
    }

    /** Recorded lines carry a trailing " nN" (and RECEIVE a " from T"); drop them for key comparison. */
    private static String stripCount(String line) {
        int cut = line.indexOf(" n");
        return cut < 0 ? line : line.substring(0, cut);
    }
}
