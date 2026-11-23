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
    private final CommitSyncState global;

    // A count shared between views for the number of objects purged,
    // and the number we saw at the last purge
    private final AtomicLong deadObjectCount;
    private long deadSetSizeAtLastPurge;

    // This thread's sync state, shared with its Thread object: its commit is the commit the thread is
    // on. Null until first use: the view can be created during VM bootstrap, before the Thread exists.
    // Never read directly, always through state().
    private CommitSyncState unboundOrState;
    private final Supplier<SyncState<?>> bindState;

    // this thread's working copies, with its uncommitted writes marked
    private final Workspace workspace = new Workspace();

    private ConflictReport pendingConflict;

    // Set when the thread starts (or, for the thread that runs main, when versioning is turned on).
    // A read before then would see version 0 regardless of the global history, so accesses assert it.
    private boolean activated;

    void activate() {
        activated = true;
    }

    // makes and folds into this thread's commits on the global history, stamping each with this thread
    private final Commit.Committer committer;

    public LinearView(Supplier<SyncState<?>> bindState, CommitSyncState global, AtomicLong deadObjectCount, Commit.Committer committer) {
        this.bindState = bindState;
        this.deadObjectCount = deadObjectCount;
        this.global = global;
        this.committer = committer;
    }

    /** This thread's sync state, bound on first use. */
    private CommitSyncState state() {
        CommitSyncState s = unboundOrState;
        if (s == null) {
            SyncState<?> bound = bindState.get();
            assert bound != null : "binding a view produced no sync state";
            // every sync state under linear was made by LinearMemoryModel, so it holds commits
            s = (CommitSyncState) bound;
            unboundOrState = s;
        }
        return s;
    }

    boolean stagingEmpty() {
        return !workspace.hasStaged();
    }

    // written fields plus written array elements, since the last commit (only for the schedule explorer)
    int stagingSize() {
        return workspace.stagedCounts().total();
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
        Commit c = state().get();
        int visited = 0;
        while (c != null) {
            // get parents before changes as a concurrent absorb swaps changes then parents
            Set<Commit> parents = c.parents();
            visited++;
            Object value = c.changesTo(number) instanceof FieldChanges f ? f.get(field) : null;
            if (value != null) {
                ModelEvents.readWalked(visited, true);
                return value;
            }
            c = parents.isEmpty() ? null : parents.iterator().next();
        }
        ModelEvents.readWalked(visited, false);
        return null;
    }

    Object readField(StaticObject holder, Field field) {
        assert activated : "a read through a view before its thread started";
        Object value = workspace.workingField(holder, field);
        if (value != null) {
            ModelEvents.workingFieldHit();
            return value;
        }

        value = getLastCommittedValue(holder.getObjectNumber(), field);

        // Otherwise there was nothing in the history, so it was the implicit
        // version 0 on the heap
        if (value == null) {
            value = field.getRawValue(holder);
        }
        workspace.addWorkingField(holder, field, value);
        return value;
    }

    void stageField(StaticObject holder, Field field, Object value) {
        assert activated : "a write through a view before its thread started";
        workspace.writeField(holder, field, value);
    }

    // Build the working copy of an array: version 0 (the raw array), then every commit on the chain from
    // the root up to our base, oldest first, so the last writer of each element wins. Arrays need a whole
    // copy up front, as the bytecodes, intrinsics and natives index the storage directly.
    private Object createArrayFromHistory(StaticObject array) {
        ModelEvents.workingArrayBuilt();
        ObjectNumber number = array.getObjectNumber();
        Object storage = array.copyOfRawArray(array.getKlass().getLanguage());

        List<Commit> chain = new ArrayList<>();   // newest first
        Commit c = state().get();
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

    Object arrayForRead(StaticObject array, int from, int to) {
        assert activated : "a read through a view before its thread started";
        return arrayStorage(array);
    }

    Object arrayForWrite(StaticObject array, int from, int to) {
        assert activated : "a write through a view before its thread started";
        Object storage = arrayStorage(array);
        workspace.writeArray(array, from, to);
        return storage;
    }

    ConflictingLocations pull() {
        return pull(Map.of());
    }

    // collected is push's copy of staging, so a write dropped for theirs is dropped from it too
    private ConflictingLocations pull(Map<ObjectNumber, ObjectChanges> collected) {
        // Walk from the global head back to our base, checking those commits against what we have
        // staged; if none conflict, install them into the working set oldest first and move our base
        // to the head.

        CommitSyncState baseState = state();
        Commit base = baseState.get();

        // Take the head and pin it under the lock: once a push moves the global head on, our head is
        // held only by its child, and compression could absorb it before we pinned it
        Commit head;
        synchronized (global) {
            head = global.get();
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
                        Set<Field> written = workspace.stagedFields(number);
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
                    } else if (changes instanceof ArrayChanges a) {
                        BitSet both = workspace.stagedElements(number);
                        both.and(a.written());
                        if (!both.isEmpty()) {
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

        ModelEvents.pulled(history.size());

        // no conflicts, so now drop our writes to fields that take theirs, from staging and from push's copy
        takenFromTheirs.forEach((number, fields) -> {
            workspace.unstage(number, fields);
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
            c.changes().forEach(workspace::applyPulled);
        }

        // Move our base to the head, then drop the pin: our state's reference now keeps it alive
        baseState.set(head);
        head.decRef();

        RefCountAbsorption.absorbIntoRecursively(head);

        return ConflictingLocations.NONE;
    }

    /** Purge dead objects from the head commit (non-recursive); the workspace drops its own as it collects. */
    private void purgeDead() {
        long deadCount = deadObjectCount.get();
        if (state().get() != null && deadCount > deadSetSizeAtLastPurge) {
            ModelEvents.Purge purge = new ModelEvents.Purge();
            purge.begin();
            state().get().purgeObjects();
            purge.commit();
            deadSetSizeAtLastPurge = deadCount;
        }
    }

    /** The staged writes as the changes of a commit; staging itself is left as it is (see emptyChanges) */
    private Map<ObjectNumber, ObjectChanges> collectChanges() {

        // The staged fields of each live object, plus the staged elements of each live array, copied: they
        // stay staged until the commit is appended, as a conflict must leave staging untouched
        return workspace.stagedChanges();
    }

    // Clear staging once the commit holding it is appended
    private void emptyChanges() {
        workspace.commitStaged();
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

        CommitSyncState baseState = state();
        Commit newGlobal;

        synchronized (global) {
            if (global.get() != baseState.get()) {
                conflicts = pull(changes);   // hopefully only a commit or two
                if (!conflicts.isEmpty() || changes.isEmpty()) {
                    return conflicts;
                }
            }

            // Our base is now the global head, held by the global and by us. Let go of ours first (the
            // global's keeps it alive): if no other thread is on it, the global's is then its only
            // reference and extend folds into it, which is safe as no pull can pin it while we hold the
            // lock. A new commit holds old as its parent, so old stays alive when the global moves on.
            Commit old = global.get();
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
