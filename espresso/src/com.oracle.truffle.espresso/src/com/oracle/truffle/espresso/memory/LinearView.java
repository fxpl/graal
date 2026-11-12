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

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

public final class LinearView implements MemoryView {

    // The global history's sync state: its commit is the head of the chain
    private final SyncState global;

    // A count shared between views for the number of objects purged,
    // and the number we saw at the last purge
    private final AtomicLong deadObjectCount;
    private long deadSetSizeAtLastPurge;

    // This thread's sync state, shared with its Thread object: its commit is the commit the thread is
    // on. Null until first use: the view can be created during VM bootstrap, before the Thread exists.
    // Never read directly, always through state().
    private SyncState unboundOrState;
    private final Supplier<SyncState> bindState;

    private final Map<ObjectNumber, WorkingObject> workingObjects = new HashMap<>(); // versioned objects this view has accessed, as this view sees them
    private final Map<ObjectNumber, WorkingObject> writtenObjects = new HashMap<>(); // ...of which written since the last commit
    private final Map<ObjectNumber, WorkingArray> workingArrays = new HashMap<>(); // versioned arrays this view has accessed, as this view sees them
    private final Map<ObjectNumber, WorkingArray> writtenArrays = new HashMap<>(); // ...of which written since the last commit


    // objects and arrays in the working set, so their entries can be dropped when they die
    private final Map<ObjectNumber, StagedReference> trackedObjects = new HashMap<>();
    private final ReferenceQueue<StaticObject> deadQueue = new ReferenceQueue<>();

    private ConflictReport pendingConflict;

    // Set when the thread starts (or, for the thread that runs main, when versioning is turned on).
    // A read before then would see version 0 regardless of the global history, so accesses assert it.
    private boolean activated;

    void activate() {
        activated = true;
    }

    // makes and folds into this thread's commits on the global history, stamping each with this thread
    private final Commit.Committer committer;

    public LinearView(Supplier<SyncState> bindState, SyncState global, AtomicLong deadObjectCount, Commit.Committer committer) {
        this.bindState = bindState;
        this.deadObjectCount = deadObjectCount;
        this.global = global;
        this.committer = committer;
    }

    /** This thread's sync state, bound on first use. */
    private SyncState state() {
        SyncState s = unboundOrState;
        if (s == null) {
            s = bindState.get();
            assert s != null : "binding a view produced no sync state";
            unboundOrState = s;
        }
        return s;
    }

    boolean stagingEmpty() {
        return writtenObjects.isEmpty() && writtenArrays.isEmpty();
    }

    // written fields plus written array elements, since the last commit (only for the schedule explorer)
    int stagingSize() {
        int writes = 0;
        for (WorkingObject w : writtenObjects.values()) {
            writes += w.written.size();
        }
        for (WorkingArray a : writtenArrays.values()) {
            writes += a.written.cardinality();
        }
        return writes;
    }

    boolean hasPendingConflict() {
        return pendingConflict != null;
    }

    void setPendingConflict(ConflictReport conflict) {
        this.pendingConflict = conflict;
    }

    ConflictReport takePendingConflict() {
        ConflictReport c = pendingConflict;
        pendingConflict = null;
        return c;
    }

    private Object getLastCommittedValue(ObjectNumber number, Field field) {
        Commit c = state().commit;
        int visited = 0;
        while (c != null) {
            // get parents before changes as a concurrent absorb swaps changes then parents
            Set<Commit> parents = c.parents();
            visited++;
            Object value = c.changesTo(number) instanceof FieldChanges f ? f.get(field) : null;
            if (value != null) {
                VersioningStats.onReadWalk(visited, true);
                return value;
            }
            c = parents.isEmpty() ? null : parents.iterator().next();
        }
        VersioningStats.onReadWalk(visited, false);
        return null;
    }

    // Drop the working entries of objects that have died, so the working set only holds live objects
    private void emptyDeadQueue() {
        Reference<?> deadReference;
        while ((deadReference = deadQueue.poll()) != null) {
            StagedReference deadObject = (StagedReference) deadReference;
            ObjectNumber number = deadObject.number;
            trackedObjects.remove(number);
            WorkingArray array = workingArrays.remove(number);
            if (array != null) {
                writtenArrays.remove(number);
                VersioningStats.onDrainArray(array.written.cardinality());
            }
            workingObjects.remove(number);
            WorkingObject written = writtenObjects.remove(number);
            if (written != null) {
                VersioningStats.onDrainStagedFields(written.written.size());
            }
        }
    }

    // Track that an object or array is in the working set, so that it can be dropped from it when it dies
    private void track(StaticObject object) {
        trackedObjects.computeIfAbsent(object.getObjectNumber(), n -> new StagedReference(object, n, deadQueue));
    }

    private WorkingObject workingObject(StaticObject holder) {
        WorkingObject w = workingObjects.get(holder.getObjectNumber());
        if (w == null) {
            w = new WorkingObject();
            workingObjects.put(holder.getObjectNumber(), w);
            track(holder);
        }
        return w;
    }

    Object readField(StaticObject holder, Field field) {
        assert activated : "a read through a view before its thread started";
        WorkingObject w = workingObject(holder);
        Object value = w.values.get(field);
        if (value != null) {
            VersioningStats.onWorkingFieldHit();
            return value;
        }

        value = getLastCommittedValue(holder.getObjectNumber(), field);

        // Otherwise there was nothing in the history, so it was the implicit
        // version 0 on the heap
        if (value == null) {
            value = field.getRawValue(holder);
        }
        w.values.put(field, value);
        return value;
    }

    void stageField(StaticObject holder, Field field, Object value) {
        assert activated : "a write through a view before its thread started";
        WorkingObject w = workingObject(holder);
        w.values.put(field, value);
        if (w.written.add(field)) {
            writtenObjects.put(holder.getObjectNumber(), w);
        }

        emptyDeadQueue();
    }

    // Build the working copy of an array: version 0 (the raw array), then every commit on the chain from
    // the root up to our base, oldest first, so the last writer of each element wins. Arrays need a whole
    // copy up front, as the bytecodes, intrinsics and natives index the storage directly.
    private WorkingArray createArrayFromHistory(StaticObject array) {
        VersioningStats.onWorkingArrayBuild();
        ObjectNumber number = array.getObjectNumber();
        Object storage = array.copyOfRawArray(array.getKlass().getLanguage());

        List<Commit> chain = new ArrayList<>();   // newest first
        Commit c = state().commit;
        while (c != null) {
            // get parents before changes as a concurrent absorb swaps changes then parents
            Set<Commit> parents = c.parents();
            chain.add(c);
            c = parents.isEmpty() ? null : parents.iterator().next();
        }
        for (Commit commit : chain.reversed()) {
            if (commit.changesTo(number) instanceof ArrayChanges changes) {
                changes.applyTo(storage);
            }
        }
        return new WorkingArray(storage);
    }

    private WorkingArray workingArray(StaticObject array) {
        WorkingArray working = workingArrays.get(array.getObjectNumber());
        if (working != null) {
            VersioningStats.onWorkingArrayHit();
            return working;
        }
        working = createArrayFromHistory(array);
        workingArrays.put(array.getObjectNumber(), working);
        track(array);
        return working;
    }

    Object arrayForRead(StaticObject array, int from, int to) {
        assert activated : "a read through a view before its thread started";
        return workingArray(array).storage;
    }

    Object arrayForWrite(StaticObject array, int from, int to) {
        assert activated : "a write through a view before its thread started";
        WorkingArray working = workingArray(array);
        working.markWrittenRange(from, to);
        writtenArrays.put(array.getObjectNumber(), working);
        return working.storage;
    }

    ConflictingLocations pull() {
        return pull(Map.of());
    }

    // collected is push's copy of staging, so a write dropped for theirs is dropped from it too
    private ConflictingLocations pull(Map<ObjectNumber, ObjectChanges> collected) {
        // Walk from the global head back to our base, checking those commits against what we have
        // staged; if none conflict, install them into the working set oldest first and move our base
        // to the head.

        SyncState baseState = state();
        Commit base = baseState.commit;

        // Take the head and pin it under the lock: once a push moves the global head on, our head is
        // held only by its child, and compression could absorb it before we pinned it
        Commit head;
        synchronized (global) {
            head = global.commit;
            if (head == base) {
                return ConflictingLocations.NONE;
            }
            head.incRef();
        }

        List<Commit> history = new ArrayList<>();
        Map<ObjectNumber, Set<Field>> fieldConflicts = new HashMap<>();
        Map<ObjectNumber, BitSet> arrayConflicts = new HashMap<>();
        Map<ObjectNumber, Set<Field>> takenFromTheirs = new HashMap<>();
        boolean mayConflict = !stagingEmpty();

        Commit curr = head;
        while (curr != base) {
            // we have to read parents before changes to agree with concurrent compression
            Set<Commit> parents = curr.parents();

            // check for conflicts if we have any staged changes
            if (mayConflict) {
                curr.changes().forEach((number, changes) -> {
                    if (changes instanceof FieldChanges f) {
                        WorkingObject mine = writtenObjects.get(number);
                        if (mine != null) {
                            Set<Field> written = mine.written;
                            for (Field field : f.fields()) {
                                if (!written.contains(field)) {
                                    continue;
                                }
                                if (field.isResolvedByTheirs()) {
                                    takenFromTheirs.computeIfAbsent(number, n -> new HashSet<>()).add(field);
                                } else {
                                    fieldConflicts.computeIfAbsent(number, n -> new HashSet<>()).add(field);
                                }
                            }
                        }
                    } else if (changes instanceof ArrayChanges a) {
                        WorkingArray mine = writtenArrays.get(number);
                        if (mine != null && mine.written.intersects(a.written())) {
                            BitSet both = (BitSet) mine.written.clone();
                            both.and(a.written());
                            arrayConflicts.computeIfAbsent(number, n -> new BitSet()).or(both);
                        }
                    }
                });
            }

            history.add(curr);
            curr = parents.isEmpty() ? null : parents.iterator().next();
        }

        if (!fieldConflicts.isEmpty() || !arrayConflicts.isEmpty()) {
            head.decRef();
            return new ConflictingLocations(fieldConflicts, arrayConflicts);
        }

        VersioningStats.onPull(history.size());

        // no conflicts, so now drop our writes to fields that take theirs, from staging and from push's copy
        takenFromTheirs.forEach((number, fields) -> {
            WorkingObject mine = writtenObjects.get(number);
            mine.written.removeAll(fields);
            if (mine.written.isEmpty()) {
                writtenObjects.remove(number);
            }
            if (collected.get(number) instanceof FieldChanges staged) {
                staged.values().keySet().removeAll(fields);
                if (staged.fields().isEmpty()) {
                    collected.remove(number);
                }
            }
        });

        // Apply while our base is still held: it bounds compression, so nothing at or below it can be
        // absorbed into a commit in history, whose changes would then differ from those checked above
        for (Commit c : history.reversed()) {
            c.changes().forEach((number, changes) -> {
                if (changes instanceof FieldChanges f) {
                    WorkingObject w = workingObjects.get(number);
                    if (w != null) {
                        f.applyTo(w);
                    }
                } else if (changes instanceof ArrayChanges a) {
                    WorkingArray working = workingArrays.get(number);
                    if (working != null) {
                        a.applyTo(working.storage);
                    }
                }
            });
        }

        // Move our base to the head, then drop the pin: our state's reference now keeps it alive
        baseState.set(head);
        head.decRef();

        RefCountAbsorption.absorbIntoRecursively(head);

        return ConflictingLocations.NONE;
    }

    /** Purge dead objects from the head commit (non-recursive) and from staging. */
    private void purgeDead() {
        long deadCount = deadObjectCount.get();
        if (state().commit != null && deadCount > deadSetSizeAtLastPurge) {
            state().commit.purgeObjects();
            deadSetSizeAtLastPurge = deadCount;
        }
        emptyDeadQueue();
    }

    /** The staged writes as the changes of a commit; staging itself is left as it is (see emptyChanges) */
    private Map<ObjectNumber, ObjectChanges> collectChanges() {

        // The written fields of each object, plus the written elements of each array, copied: the written
        // marks stay until the commit is appended, as a conflict must leave staging untouched
        Map<ObjectNumber, ObjectChanges> changes = new HashMap<>();
        for (Map.Entry<ObjectNumber, WorkingObject> e : writtenObjects.entrySet()) {
            WorkingObject object = e.getValue();
            changes.put(e.getKey(), FieldChanges.of(object));
        }

        for (Map.Entry<ObjectNumber, WorkingArray> e : writtenArrays.entrySet()) {
            WorkingArray array = e.getValue();
            changes.put(e.getKey(), ArrayChanges.of(array));
        }

        return changes;
    }

    // Clear the written marks once the commit holding them is appended. Both kinds of write are counted
    // here for the stats: staging only grows between commits, so its size now is also its peak
    private void emptyChanges() {
        int fields = 0;
        for (Map.Entry<ObjectNumber, WorkingObject> e : writtenObjects.entrySet()) {
            WorkingObject object = e.getValue();
            fields += object.written.size();
            object.written.clear();
        }
        writtenObjects.clear();

        long arrayElements = 0;
        int arrays = 0;
        for (Map.Entry<ObjectNumber, WorkingArray> e : writtenArrays.entrySet()) {
            WorkingArray array = e.getValue();
            arrayElements += array.written.cardinality();
            arrays++;
            array.written.clear();
        }
        writtenArrays.clear();

        if (fields > 0 || arrays > 0 || arrayElements > 0) {
            VersioningStats.observeStaging(fields + arrayElements);
            VersioningStats.onCommit(fields, arrays, arrayElements);
        }
    }

    // In linear every push is an atomic pull then push. To hold the global lock briefly, pull first
    // (taking the lock only to read the head), then under the lock pull again if the head has moved
    // since (usually a commit or two) and append our commit
    ConflictingLocations push() {
        purgeDead();

        ConflictingLocations conflicts = pull();
        if (!conflicts.isEmpty()) {
            return conflicts;
        }

        Map<ObjectNumber, ObjectChanges> changes = collectChanges();
        if (changes.isEmpty()) {
            return ConflictingLocations.NONE;
        }

        SyncState baseState = state();
        Commit newGlobal;

        synchronized (global) {
            if (global.commit != baseState.commit) {
                conflicts = pull(changes);   // hopefully only a commit or two
                if (!conflicts.isEmpty() || changes.isEmpty()) {
                    return conflicts;
                }
            }

            // Our base is now the global head, held by the global and by us. Let go of ours first (the
            // global's keeps it alive): if no other thread is on it, the global's is then its only
            // reference and extend folds into it, which is safe as no pull can pin it while we hold the
            // lock. A new commit holds old as its parent, so old stays alive when the global moves on.
            Commit old = global.commit;
            baseState.set(null);
            newGlobal = old == null ? committer.first(changes) : committer.extend(old, changes);
            global.set(newGlobal);      // no-op when it folded
            baseState.set(newGlobal);
        }

        emptyChanges();
        RefCountAbsorption.absorbIntoRecursively(newGlobal);

        return ConflictingLocations.NONE;
    }

}
