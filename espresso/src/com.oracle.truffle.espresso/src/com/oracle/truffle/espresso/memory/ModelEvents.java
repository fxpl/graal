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

import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * What the versioned models do, as Flight Recorder events. Every event is disabled unless a recording
 * enables it, so with no recording each emitter is an {@code isEnabled()} test the JIT folds away. A
 * reader of the recording (live or from a file) aggregates them, so nothing is counted in the engine.
 *
 * <p>Events without a duration mark that something happened. Spans ({@link Merge}, {@link MergeWalk},
 * {@link AbsorbWait}, {@link Absorb}, {@link CompressWait}, {@link Compress}) cover the work they name and
 * nest on their thread, so a reader takes a span's own time as its duration less the spans inside it.
 */
final class ModelEvents {

    private static final String PREFIX = "espresso.syncshare.";

    private ModelEvents() {
    }

    // The commit history

    @Name(PREFIX + "Committed") @Label("Committed") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class Committed extends Event {
        @Label("Field writes") int fieldChanges;
        @Label("Arrays written") int arrays;
        @Label("Array elements written") long arrayElements;
    }

    static void committed(int fieldChanges, int arrays, long arrayElements) {
        Committed e = new Committed();
        if (e.isEnabled()) {
            e.fieldChanges = fieldChanges;
            e.arrays = arrays;
            e.arrayElements = arrayElements;
            e.commit();
        }
    }

    @Name(PREFIX + "MergeCommitted") @Label("Merge committed") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class MergeCommitted extends Event {
    }

    static void mergeCommitted() {
        MergeCommitted e = new MergeCommitted();
        if (e.isEnabled()) {
            e.commit();
        }
    }

    @Name(PREFIX + "Folded") @Label("Folded") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class Folded extends Event {
    }

    static void folded() {
        Folded e = new Folded();
        if (e.isEnabled()) {
            e.commit();
        }
    }

    @Name(PREFIX + "Released") @Label("Released") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class Released extends Event {
    }

    static void released() {
        Released e = new Released();
        if (e.isEnabled()) {
            e.commit();
        }
    }

    @Name(PREFIX + "StagingObserved") @Label("Staging observed") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class StagingObserved extends Event {
        @Label("Staged writes") long size;
    }

    static void stagingObserved(long size) {
        StagingObserved e = new StagingObserved();
        if (e.isEnabled()) {
            e.size = size;
            e.commit();
        }
    }

    // Absorption and compression

    @Name(PREFIX + "Absorbed") @Label("Absorbed") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class Absorbed extends Event {
        @Label("Commits absorbed") int commits;
    }

    static void absorbed(int commits) {
        Absorbed e = new Absorbed();
        if (e.isEnabled()) {
            e.commits = commits;
            e.commit();
        }
    }

    /** The time a ref-count absorption waits for the absorb lock. */
    @Name(PREFIX + "AbsorbWait") @Label("Absorb wait") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class AbsorbWait extends Event {
    }

    /** A ref-count absorption, holding the absorb lock. */
    @Name(PREFIX + "Absorb") @Label("Absorb") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class Absorb extends Event {
    }

    /** The time a seen-set compression waits for the graph's write side. */
    @Name(PREFIX + "CompressWait") @Label("Compress wait") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class CompressWait extends Event {
    }

    /** A seen-set compression, holding the graph's write side with every other graph user stopped. */
    @Name(PREFIX + "Compress") @Label("Compress") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class Compress extends Event {
    }

    // Dead objects

    @Name(PREFIX + "Purged") @Label("Purged") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class Purged extends Event {
        @Label("Field writes removed") int fieldEntries;
        @Label("Arrays removed") int arrays;
    }

    static void purged(int fieldEntries, int arrays) {
        Purged e = new Purged();
        if (e.isEnabled()) {
            e.fieldEntries = fieldEntries;
            e.arrays = arrays;
            e.commit();
        }
    }

    @Name(PREFIX + "StagedFieldsDrained") @Label("Staged fields drained") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class StagedFieldsDrained extends Event {
        @Label("Staged writes dropped") long fields;
    }

    static void stagedFieldsDrained(long fields) {
        StagedFieldsDrained e = new StagedFieldsDrained();
        if (e.isEnabled()) {
            e.fields = fields;
            e.commit();
        }
    }

    @Name(PREFIX + "ArrayDrained") @Label("Array drained") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class ArrayDrained extends Event {
        @Label("Written elements dropped") long writtenElements;
    }

    static void arrayDrained(long writtenElements) {
        ArrayDrained e = new ArrayDrained();
        if (e.isEnabled()) {
            e.writtenElements = writtenElements;
            e.commit();
        }
    }

    @Name(PREFIX + "CleanerRegistered") @Label("Cleaner registered") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class CleanerRegistered extends Event {
    }

    static void cleanerRegistered() {
        CleanerRegistered e = new CleanerRegistered();
        if (e.isEnabled()) {
            e.commit();
        }
    }

    /** Emitted on the Cleaner's thread, after a GC found a registered object unreachable. */
    @Name(PREFIX + "MarkedForPurging") @Label("Marked for purging") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class MarkedForPurging extends Event {
    }

    static void markedForPurging() {
        MarkedForPurging e = new MarkedForPurging();
        if (e.isEnabled()) {
            e.commit();
        }
    }

    // Reads

    @Name(PREFIX + "ReadWalked") @Label("Read walked") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class ReadWalked extends Event {
        @Label("Commits visited") int visited;
        @Label("Found a committed value") boolean found;
    }

    static void readWalked(int visited, boolean found) {
        ReadWalked e = new ReadWalked();
        if (e.isEnabled()) {
            e.visited = visited;
            e.found = found;
            e.commit();
        }
    }

    @Name(PREFIX + "WorkingFieldHit") @Label("Working field hit") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class WorkingFieldHit extends Event {
    }

    static void workingFieldHit() {
        WorkingFieldHit e = new WorkingFieldHit();
        if (e.isEnabled()) {
            e.commit();
        }
    }

    @Name(PREFIX + "WorkingArrayHit") @Label("Working array hit") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class WorkingArrayHit extends Event {
    }

    static void workingArrayHit() {
        WorkingArrayHit e = new WorkingArrayHit();
        if (e.isEnabled()) {
            e.commit();
        }
    }

    @Name(PREFIX + "WorkingArrayBuilt") @Label("Working array built") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class WorkingArrayBuilt extends Event {
    }

    static void workingArrayBuilt() {
        WorkingArrayBuilt e = new WorkingArrayBuilt();
        if (e.isEnabled()) {
            e.commit();
        }
    }

    // Sharing

    @Name(PREFIX + "Allocated") @Label("Allocated") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class Allocated extends Event {
        @Label("Array") boolean array;
    }

    static void allocated(boolean array) {
        Allocated e = new Allocated();
        if (e.isEnabled()) {
            e.array = array;
            e.commit();
        }
    }

    @Name(PREFIX + "Shared") @Label("Shared") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class Shared extends Event {
        @Label("Array") boolean array;
    }

    static void shared(boolean array) {
        Shared e = new Shared();
        if (e.isEnabled()) {
            e.array = array;
            e.commit();
        }
    }

    @Name(PREFIX + "Versioned") @Label("Versioned") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class Versioned extends Event {
        @Label("Array") boolean array;
    }

    static void versioned(boolean array) {
        Versioned e = new Versioned();
        if (e.isEnabled()) {
            e.array = array;
            e.commit();
        }
    }

    // Linear pulls

    @Name(PREFIX + "Pulled") @Label("Pulled") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class Pulled extends Event {
        @Label("Commits walked and installed") int commits;
    }

    static void pulled(int commits) {
        Pulled e = new Pulled();
        if (e.isEnabled()) {
            e.commits = commits;
            e.commit();
        }
    }

    // Branching merges

    /** How a branching merge ended. */
    enum MergeOutcome {
        ADOPTED,        // no head of our own, took theirs, no walk
        NOTHING,        // they had no head, no walk
        SAME,           // the same head, no walk
        EPOCH_INCLUDED, // one thread made both heads, theirs first, so already ours, no walk
        INCLUDED,       // walked, theirs was already in our history
        CONFLICT,       // walked, both sides wrote a location
        FAST_FORWARD,   // walked, ours was in their history, so we took their head
        MERGED          // walked, a merge commit was made
    }

    @Name(PREFIX + "MergeSkipped") @Label("Merge skipped") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class MergeSkipped extends Event {
        @Label("Outcome") String outcome;
    }

    /** A merge that ended without walking the histories. */
    static void mergeSkipped(MergeOutcome outcome) {
        MergeSkipped e = new MergeSkipped();
        if (e.isEnabled()) {
            e.outcome = outcome.name();
            e.commit();
        }
    }

    /** A merge that walked the histories, from the walk's start to the merge's end. */
    @Name(PREFIX + "Merge") @Label("Merge") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class Merge extends Event {
        @Label("Outcome") String outcome;
        @Label("Commits coloured") int visited;
        @Label("Only in ours") int mineOnly;
        @Label("Only in theirs") int theirsOnly;
    }

    /** A merge's colouring walk. */
    @Name(PREFIX + "MergeWalk") @Label("Merge walk") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class MergeWalk extends Event {
    }

    // The state left at the end, emitted once as the context closes

    @Name(PREFIX + "SyncStates") @Label("Sync states") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class SyncStates extends Event {
        @Label("Kind") String kind;
        @Label("Live") long live;
        @Label("Holding a commit") long holding;
        @Label("Distinct commits held") long distinct;
    }

    @Name(PREFIX + "LiveGraph") @Label("Live graph") @Category({"Espresso", "Sync-as-Share"}) @Enabled(false) @StackTrace(false)
    static final class LiveGraph extends Event {
        @Label("Reachable commits") long reachable;
        @Label("Depth") long depth;
        @Label("Width") long width;
        @Label("Deepest generation") long generation;
        @Label("Commits held by a sync state") long pinnedCommits;
        @Label("References nobody accounts for") long missingRefs;
    }
}
