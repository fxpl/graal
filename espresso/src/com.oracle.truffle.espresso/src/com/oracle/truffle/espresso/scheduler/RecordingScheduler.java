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
 * Records the order threads pass through the meaningful synchronization seams — the cross-thread
 * information handoffs — to a schedule file that {@link ReplayScheduler} can force on a later run
 * (design/schedule-explorer.md §5.4 mode 2). Recording is passive: it logs, it never blocks, so it
 * cannot deadlock or perturb the run beyond a per-seam critical section.
 *
 * <p>The recorded schedule is the sequence of handoff endpoints, not every seam:
 * <ul>
 * <li>a <b>PUBLISH</b> when a thread releases a subject with writes staged (a flow-commit), and</li>
 * <li>a <b>RECEIVE</b> when a different thread later acquires that same subject (consuming the
 * publish).</li>
 * </ul>
 * Seams that publish nothing, or that no other thread synchronizes with, move no information and are
 * dropped — under Sync-as-Share their order cannot change any thread's view (§3a). This is what makes
 * the schedule short and, crucially, what makes forcing only these events enough to pin the outcome.
 *
 * <p>Selected by {@code --java.ScheduleDrive=record --java.ScheduleFile=<path>}.
 */
public final class RecordingScheduler extends AbstractDriveScheduler {

    private final PrintStream err = System.err;
    private final String path;

    // Per subject: which app thread last flow-published it, and how many writes. A later cross-thread
    // acquire of the subject consumes this into a RECEIVE entry.
    private final Map<StaticObject, String> flowPublisher = new ConcurrentHashMap<>();
    private final Map<StaticObject, Integer> flowCount = new ConcurrentHashMap<>();

    private final List<Entry> entries = Collections.synchronizedList(new ArrayList<>());

    private record Entry(long seq, String thread, String kind, SyncOp op, int subjectId, int count, String from) {
    }

    public RecordingScheduler(String path) {
        this.path = path;
    }

    @Override
    protected void onAppSeam(long seq, String thread, SyncOp op, StaticObject subject,
                    boolean hasSubject, int subjectId, int pendingWrites) {
        boolean flow = op.isRelease() && pendingWrites > 0 && hasSubject;
        if (flow) {
            flowPublisher.put(subject, thread);
            flowCount.put(subject, pendingWrites);
            entries.add(new Entry(seq, thread, "PUBLISH", op, subjectId, pendingWrites, null));
        }
        if (op.isAcquire() && hasSubject) {
            String publisher = flowPublisher.get(subject);
            if (publisher != null && !publisher.equals(thread)) {
                int count = flowCount.getOrDefault(subject, 0);
                flowPublisher.remove(subject);
                flowCount.remove(subject);
                entries.add(new Entry(seq, thread, "RECEIVE", op, subjectId, count, publisher));
            }
        }
    }

    @Override
    @TruffleBoundary
    public void report() {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> Long.compare(a.seq(), b.seq()));
        StringBuilder sb = new StringBuilder();
        sb.append("# sync-as-share schedule v1\n");
        sb.append("# handoff order: PUBLISH (release with staged writes) / RECEIVE (cross-thread acquire)\n");
        sb.append("# columns: kind thread op subjectId count [from]\n");
        for (Entry e : sorted) {
            sb.append(e.kind()).append(' ').append(e.thread()).append(' ').append(e.op())
                            .append(" S").append(e.subjectId()).append(" n").append(e.count());
            if (e.from() != null) {
                sb.append(" from ").append(e.from());
            }
            sb.append('\n');
        }
        if (path == null || path.isEmpty()) {
            err.print(sb);
            err.println("[schedule] (no ScheduleFile set — printed above; pass --java.ScheduleFile=<path> to save)");
            return;
        }
        try {
            Files.writeString(Path.of(path), sb.toString(), StandardCharsets.UTF_8);
            err.printf("[schedule] recorded %d handoff(s) to %s%n", sorted.size(), path);
        } catch (IOException ex) {
            err.printf("[schedule] failed to write %s: %s%n", path, ex);
            err.print(sb);
        }
    }
}
