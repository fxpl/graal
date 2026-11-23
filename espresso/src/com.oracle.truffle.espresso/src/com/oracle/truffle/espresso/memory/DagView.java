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
 * You have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.espresso.memory;

import java.lang.Integer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.memory.ModelEvents.MergeOutcome;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * A guest thread's view in the branching model, kept as a commit DAG (the prototype's
 * {@code branching::LocalVersionStore}): a {@code head} into the commit DAG, and working copies of the
 * versioned objects and arrays it has accessed, with the writes it has not yet committed marked.
 *
 * <p>Not thread-safe, and does not need to be: a view is owned by one guest thread. Commits are
 * immutable {@link Commit} nodes shared across views (safe publication via final fields).
 */
public final class DagView implements BranchingView {

    // This thread's branch, shared with its Thread object. Null until first use: the view can be created
    // during VM bootstrap, before the Thread exists. Never read directly, always through state().
    private CommitSyncState unboundOrState;
    private final Supplier<SyncState<?>> bindState;
    // this thread's working copies, with its uncommitted writes marked
    private final Workspace workspace = new Workspace();

    private ConflictReport pendingConflict; // found by a synchronisation action, raised at the next safe point

    // Set when the thread starts (or, for the thread that runs main, when versioning is turned on).
    // A read before then would see version 0 regardless of what the thread inherits, so accesses
    // assert it.
    private boolean activated;

    @Override
    public void activate() {
        activated = true;
    }

    // A count shared between views for the number of objects purged,
    // and the number we saw at the last purge
    private final AtomicLong deadObjectCount;
    private long deadSetSizeAtLastPurge;


    // commits visited by the last read walk, to pre-size the next walk's visited set
    private int lastWalkSize = 16;

    // commits coloured by the last merge walk, to pre-size the next walk's colour map
    private int lastMergeWalkSize = 16;

    // makes and folds into this thread's commits, stamping each with this thread
    private final Commit.Committer committer;

    public DagView(Supplier<SyncState<?>> bindState, AtomicLong deadObjectCount, Commit.Committer committer) {
        this.bindState = bindState;
        this.deadObjectCount = deadObjectCount;
        this.committer = committer;
    }

    /** This thread's sync state, bound on first use. */
    private CommitSyncState state() {
        CommitSyncState s = unboundOrState;
        if (s == null) {
            SyncState<?> bound = bindState.get();
            assert bound != null : "binding a view produced no sync state";
            s = asCommitState(bound);
            unboundOrState = s;
        }
        return s;
    }

    // every sync state under this view's model was made by DagRepresentation, so it holds commits
    private static CommitSyncState asCommitState(SyncState<?> state) {
        return (CommitSyncState) state;
    }

    private void setHead(Commit c) {
        state().set(c);
        // a join pulls from this state, so it carries our clock as a pushed state would
        state().clock = committer.clock();
    }

    boolean stagingEmpty() {
        return !workspace.hasStaged();
    }

    @Override
    public int pendingWriteCount() {
        return workspace.stagedCounts().total();
    }

    @Override
    public void writeField(StaticObject holder, Field field, Object value) {
        assert activated : "a write through a view before its thread started";
        workspace.writeField(holder, field, value);
    }

    /** The value this view sees: its working value, else the committed value, else version 0 (the raw slot). */
    @Override
    public Object readField(StaticObject holder, Field field) {
        assert activated : "a read through a view before its thread started";
        Object value = workspace.workingField(holder, field);
        if (value != null) {
            ModelEvents.workingFieldHit();
            return value;
        }

        if (state().get() != null) {
            value = getLastCommittedValue(holder.getObjectNumber(), field);
        }
        if (value == null) {
            value = field.getRawValue(holder);
        }
        workspace.addWorkingField(holder, field, value);
        return value;
    }

    /** The newest committed value of the field under the head, else null. The head must be set. */
    private Object getLastCommittedValue(ObjectNumber number, Field field) {
        // a walk: holds the graph's read side, so nothing rewrites the commits it passes
        Commit.GRAPH.readLock().lock();
        try {
            return findLastCommittedValue(number, field);
        } finally {
            Commit.GRAPH.readLock().unlock();
        }
    }

    private Object findLastCommittedValue(ObjectNumber number, Field field) {
        // The pathological cases are:
        // 1) the value is all the way back at version 0

        // Walk back through the DAG, building a priority queue where
        // the newest commits are to be explored first, the moment we find a
        // value, we know we have the newest value, if we don't find a value
        // we add the parents to the queue (in priority order).
        // We are assuming that this a conflict free graph, so there can
        // only be _one_ latest value

        Set<Commit> visited = HashSet.newHashSet(lastWalkSize);
        Queue<Commit> heads = new PriorityQueue<Commit>((commit1, commit2) ->
            Integer.compare(commit2.generation(), commit1.generation())
        );

        visited.add(state().get());

        Object found = null;
        Commit current = state().get();
        while (current != null) {
            runRefCountAbsorption(current);

            // parents before changes: a concurrent absorb swaps changes then parents, so new parents
            // guarantee the new changes are seen too
            Set<Commit> parents = current.parents();
            found = current.changesTo(number) instanceof FieldChanges f ? f.get(field) : null;
            if (found != null) {
                break;
            }

            // Most of the history is chains of single parent commits, so if
            // there is only one parent and it is at least as new as anything
            // waiting in the queue, it would be the next thing popped anyway.
            // In that case just step straight to it and skip the queue.
            boolean isOnlyParent = parents.size() == 1;
            Commit next = null;
            for (Commit p : parents) {
                if (!visited.add(p)) {
                    continue;
                }
                boolean isNextToPop = heads.isEmpty() || p.generation() >= heads.peek().generation();
                if (isOnlyParent && isNextToPop) {
                    next = p;
                } else {
                    heads.add(p);
                }
            }
            current = next != null ? next : heads.poll();
        }

        lastWalkSize = visited.size();
        ModelEvents.readWalked(visited.size(), found != null);
        return found;
    }

    /** Turn staged writes into a new commit on this thread's branch (no-op if nothing staged). */
    private void commitStaging() {
        // before merging the changes into the head, first try to
        // purge any dead objects from the head commit (non-recursive)
        long deadCount = deadObjectCount.get();
        if (state().get() != null && deadCount > deadSetSizeAtLastPurge) {
            ModelEvents.Purge purge = new ModelEvents.Purge();
            purge.begin();
            state().get().purgeObjects();
            purge.commit();
            deadSetSizeAtLastPurge = deadCount;
        }

        // The staged fields of each live object, plus the staged elements of each live array, which are then
        // no longer staged; the working values stay, as they are now what the head says
        Map<ObjectNumber, ObjectChanges> changes = workspace.stagedChanges();
        workspace.commitStaged();

        if (changes.isEmpty()) {
            return;
        }

        ModelEvents.Committing span = new ModelEvents.Committing();
        span.begin();
        VersioningStats.countCommit();

        Commit head = state().get();
        Commit next = head == null ? committer.first(changes) : committer.extend(head, changes);
        if (next != head) {
            VersioningStats.describeCommit(next, state(), false);
        }
        setHead(next);
        span.commit();
    }

    private void runSeenSetCompression() {
        SeenSetCompression.tryCompress();
    }

    private static void runRefCountAbsorption(Commit into) {
        RefCountAbsorption.absorbInto(into);
    }

    // absorb into our head and on up through its new parents
    private void runRefCountAbsorptionRecursively() {
        Commit head = state().get();
        if (head == null) {
            return;
        }
        RefCountAbsorption.absorbIntoRecursively(head);
    }

    private enum CommitColour { MINE, THEIRS, BOTH; };

    /**
     * Merge the branch tip {@code otherHead} into this branch. Returns a set of locations written
     * by both branches (empty means none). Staging must be empty — every sync action (lock
     * acquire, join, volatile read/write) commits first.
     */
    ConflictingLocations merge(CommitSyncState theirState) {
        // a walk: holds the graph's read side for the whole merge, so nothing rewrites the commits it
        // colours or installs from; anything that rewrites the graph runs after the unlock
        ConflictingLocations conflicts;
        Commit.GRAPH.readLock().lock();
        try {
            conflicts = mergeHeads(theirState);
        } finally {
            Commit.GRAPH.readLock().unlock();
        }
        // a clean pull takes in their clock, so a later pull of what they had can skip the walk
        if (conflicts.isEmpty() && theirState.get() != null) {
            committer.join(theirState.clock);
        }
        // our head may have moved, and collapsing takes the write side, so only now the read side is released
        runRefCountAbsorptionRecursively();
        runSeenSetCompression();
        return conflicts;
    }

    private ConflictingLocations mergeHeads(CommitSyncState theirState) {
        Commit theirHead = theirState.get();
        assert stagingEmpty() : "merging with uncommitted writes";
        Commit myHead = state().get();
        if (myHead == null) {
            // either pickup the other commit as head (there is nothing to merge)
            // or the genuinely is no operation to do here
            if (theirHead != null) {
                // the head moves, so the clock moves, or one clock value would name two heads
                committer.tick();
                setHead(theirHead);
                // our working objects and arrays were read from version 0 alone, so may be stale against
                // the adopted history; with no head we have never committed, so none has written marks
                workspace.clear();
                ModelEvents.mergeSkipped(MergeOutcome.ADOPTED);
            } else {
                ModelEvents.mergeSkipped(MergeOutcome.NOTHING);
            }
            return ConflictingLocations.NONE;
        }

        if (theirHead == null) {
            // just return as there is nothing to merge
            ModelEvents.mergeSkipped(MergeOutcome.NOTHING);
            return ConflictingLocations.NONE;
        }

        if (theirHead == myHead) {
            // no point in doing this
            ModelEvents.mergeSkipped(MergeOutcome.SAME);
            return ConflictingLocations.NONE;
        }

        // their state was last set at a point our clock already covers, so their head is in our history
        if (theirState.clock.happensBefore(committer.clock())) {
            // they are an ancestor of us so nothing to do
            ModelEvents.mergeSkipped(MergeOutcome.EPOCH_INCLUDED);
            return ConflictingLocations.NONE;
        }

        ModelEvents.Merge merge = new ModelEvents.Merge();
        merge.begin();
        ModelEvents.MergeWalk walk = new ModelEvents.MergeWalk();
        walk.begin();

        List<Commit> mineOnly = new ArrayList<>();
        List<Commit> theirsOnly = new ArrayList<>();

        Queue<Commit> heads = new PriorityQueue<>((c1, c2) ->
            Integer.compare(c2.generation(), c1.generation())
        );
        Map<Commit, CommitColour> colours = HashMap.newHashMap(lastMergeWalkSize);

        heads.add(myHead);
        heads.add(theirHead);

        colours.put(myHead, CommitColour.MINE);
        colours.put(theirHead, CommitColour.THEIRS);

        int nonBoth = 2;
        while (nonBoth > 0) {
            Commit head = heads.remove();
            runRefCountAbsorption(head);
            CommitColour colour = colours.get(head);

            // We've now visited everything below this node,
            // So we now know the colour of the node and whose
            // change set the changes belong to
            if (colour == CommitColour.MINE) {
                mineOnly.add(head);
                nonBoth--;
            } else if (colour == CommitColour.THEIRS) {
                theirsOnly.add(head);
                nonBoth--;
            }

            for (Commit p : head.parents()) {
                CommitColour parentColour = colours.get(p);

                if (parentColour == null) {
                    colours.put(p, colour);
                    heads.add(p);

                    if (colour != CommitColour.BOTH) {
                        nonBoth++;
                    }
                } else if (parentColour != colour) {
                    colours.put(p, CommitColour.BOTH);

                    if (parentColour != CommitColour.BOTH) {
                        nonBoth--;
                    }
                }
            }
        }
        lastMergeWalkSize = colours.size();
        walk.commit();

        if (theirsOnly.isEmpty()) {
            // they are an ancestor of us so nothing to do
            recordWalkedMerge(merge, MergeOutcome.INCLUDED, colours.size(), mineOnly, theirsOnly);
            return ConflictingLocations.NONE;
        }

        Map<ObjectNumber, Set<Field>> mineFieldsWritten = new HashMap<>();
        Map<ObjectNumber, BitSet> mineArrayWritten = new HashMap<>();
        for (Commit c : mineOnly) {
            c.changes().forEach((number, changes) -> {
                if (changes instanceof FieldChanges f) {
                    mineFieldsWritten.computeIfAbsent(number, n -> new HashSet<>()).addAll(f.fields());
                } else if (changes instanceof ArrayChanges a) {
                    mineArrayWritten.computeIfAbsent(number, n -> new BitSet()).or(a.written());
                }
            });
        }

        // First check for conflicts between mine and theirs
        Map<ObjectNumber, FieldChanges> resolved = new HashMap<>();
        if (!mineFieldsWritten.isEmpty() || !mineArrayWritten.isEmpty()) {
            Map<ObjectNumber, Set<Field>> fieldConflicts = new HashMap<>();
            Map<ObjectNumber, BitSet> arrayConflicts = new HashMap<>();
            for (Commit c : theirsOnly) {
                c.changes().forEach((number, changes) -> {
                    if (changes instanceof FieldChanges f) {
                        Set<Field> mine = mineFieldsWritten.get(number);
                        if (mine != null) {
                            for (Field field : f.fields()) {
                                if (!mine.contains(field)) {
                                    continue;
                                }
                                if (field.isResolvedByTheirs()) {
                                    // theirsOnly is newest first, so the first value met is the one to take
                                    FieldChanges taken = resolved.computeIfAbsent(number, n -> FieldChanges.of(new HashMap<>()));
                                    taken.values().putIfAbsent(field, f.get(field));
                                } else {
                                    fieldConflicts.computeIfAbsent(number, n -> new HashSet<>()).add(field);
                                }
                            }
                        }
                    } else if (changes instanceof ArrayChanges a) {
                        BitSet mine = mineArrayWritten.get(number);
                        if (mine != null && mine.intersects(a.written())) {
                            BitSet both = (BitSet) mine.clone();
                            both.and(a.written());
                            arrayConflicts.computeIfAbsent(number, n -> new BitSet()).or(both);
                        }
                    }
                });
            }
            if (!fieldConflicts.isEmpty() || !arrayConflicts.isEmpty()) {
                recordWalkedMerge(merge, MergeOutcome.CONFLICT, colours.size(), mineOnly, theirsOnly);
                return new ConflictingLocations(fieldConflicts, arrayConflicts);
            }
        }

        // No conflicts, so install their writes into our working. This is instead
        // of dropping the value and picking up the new values on read
        for (Commit c : theirsOnly.reversed()) {
            c.changes().forEach(workspace::applyPulled);
        }

        if (mineOnly.isEmpty()) {
            // we are some ancestor of theirs, so just fast forward (merge compresses once the read side is
            // released)
            committer.tick();
            setHead(theirHead);
            recordWalkedMerge(merge, MergeOutcome.FAST_FORWARD, colours.size(), mineOnly, theirsOnly);
            return ConflictingLocations.NONE;
        }

        // create the merge of the parents, holding theirs for each resolved field so every walk finds the same value
        Commit mergeCommit = committer.merge(state().get(), theirHead, resolved);
        ModelEvents.mergeCommitted();
        VersioningStats.describeCommit(mergeCommit, state(), true);
        setHead(mergeCommit);
        recordWalkedMerge(merge, MergeOutcome.MERGED, colours.size(), mineOnly, theirsOnly);
        return ConflictingLocations.NONE;
    }

    private static void recordWalkedMerge(ModelEvents.Merge merge, MergeOutcome outcome, int visited, List<Commit> mineOnly,
                    List<Commit> theirsOnly) {
        if (merge.shouldCommit()) {
            merge.outcome = outcome.name();
            merge.visited = visited;
            merge.mineOnly = mineOnly.size();
            merge.theirsOnly = theirsOnly.size();
            merge.commit();
        }
    }

    // pull from another commit and report any conflicting locations
    @Override
    public ConflictingLocations pull(SyncState<?> from) {
        commitStaging();
        return merge(asCommitState(from));
    }

    // the thread's last commit, which its own state then holds for a join to pull
    @Override
    public void end() {
        commitStaging();

        // after commit, try to compress the DAG
        runRefCountAbsorptionRecursively();
        runSeenSetCompression();
    }

    // commit, compress and push the changes to the 'to' location
    @Override
    public void push(SyncState<?> pushedTo) {
        CommitSyncState to = asCommitState(pushedTo);
        // let go of to's reference first: if to held our head, the head may then be exclusive to us
        // and the commit can fold into it
        Commit old = to.get();
        to.set(null);
        commitStaging();
        to.set(state().get());
        to.clock = committer.clock();
        // after the lock has moved, so the compression sees it holding our head
        runRefCountAbsorptionRecursively();
        runSeenSetCompression();

        // when we let go of a commit, we may now enable a lot more compressions
        if (old != null && old.getRefCount() > 0) {
            runRefCountAbsorption(old);
        }
    }

    // Walk back to the root and find the ordered writes to the array reachable
    // from the current head, and then apply those writes.
    // We do this more general walk, instead of the stop at a first commit for a field, because arrays require
    // writes to all of their indexes, so figuring out which array commits shadow which others is more complicated
    // than collecting history and applying it
    private Object createArrayFromHistory(StaticObject array) {
        // a walk: holds the graph's read side, so nothing rewrites the commits it passes
        Commit.GRAPH.readLock().lock();
        try {
            return buildArrayFromHistory(array);
        } finally {
            Commit.GRAPH.readLock().unlock();
        }
    }

    private Object buildArrayFromHistory(StaticObject array) {
        ModelEvents.workingArrayBuilt();
        ObjectNumber number = array.getObjectNumber();
        Object storage = array.copyOfRawArray(array.getKlass().getLanguage());

        // We have no history of changes yet, so we must be at version 0,
        // so just return the copy
        if (state().get() == null) {
            return storage;
        }

        Set<Commit> order = LinkedHashSet.newLinkedHashSet(lastWalkSize);  // ancestors before descendants
        Set<Commit> visited = HashSet.newHashSet(lastWalkSize);
        Deque<Commit> heads = new ArrayDeque<>();
        heads.push(state().get());

        while (!heads.isEmpty()) {
            Commit c = heads.peek();

            if (order.contains(c)) {
                // If we have already ordered this thing in history just skip over it
                heads.pop();
            } else if (visited.add(c)) {
                // Otherwise it is the first time we have seen this node, so push its parents to the front of the
                // stack, leaving this child where it is so we revisit it later once the parents have been
                // ordered
                for (Commit p : c.parents()) {
                    if (!order.contains(p)) {
                        heads.push(p);
                    }
                }
            } else {
                // Now we know where the child lives in the order of things so add it to the ordered order set
                heads.pop();
                order.add(c);
            }
        }
        lastWalkSize = order.size();

        // Apply each commit's writes in that order, so the last writer of each index wins.
        for (Commit c : order) {
            if (c.changesTo(number) instanceof ArrayChanges changes) {
                changes.applyTo(storage);
            }
        }

        return storage;
    }

    @Override
    public Object arrayForRead(StaticObject array, int from, int to) {
        assert activated : "a read through a view before its thread started";
        return arrayStorage(array);
    }

    @Override
    public Object arrayForWrite(StaticObject array, int from, int to) {
        assert activated : "a write through a view before its thread started";
        Object storage = arrayStorage(array);
        workspace.writeArray(array, from, to);
        return storage;
    }

    // the storage of the thread's working array for array, built from history if it has none
    private Object arrayStorage(StaticObject array) {
        Object storage = workspace.workingArray(array);
        if (storage != null) {
            ModelEvents.workingArrayHit();
            return storage;
        }
        storage = createArrayFromHistory(array);
        workspace.addWorkingArray(array, storage);
        return storage;
    }

    // --- Pending conflict (raised at the bytecode safe point, asked through the model) ---

    @Override
    public void setPendingConflict(ConflictReport conflict) {
        this.pendingConflict = conflict;
    }

    @Override
    public boolean hasPendingConflict() {
        return pendingConflict != null;
    }

    @Override
    public ConflictReport takePendingConflict() {
        ConflictReport c = pendingConflict;
        pendingConflict = null;
        return c;
    }
}
