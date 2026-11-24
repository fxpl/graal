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
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * A thread's workspace, in two halves. What the thread sees is its working objects and arrays, the
 * versioned objects and arrays it has accessed, brought in from its history or pulled from another thread.
 * What the thread has not committed is the writes it has staged on them since its last commit. Every view
 * keeps one, whatever its model keeps committed versions in. A working object or array is dropped once its
 * object is collected. Owned by one guest thread.
 */
final class Workspace {

    /** How much is staged, for the stats. */
    record Staged(int fields, int arrays, long elements) {
        /** Fields plus array elements. */
        int total() {
            return fields + (int) elements;
        }
    }

    private final Map<ObjectNumber, WorkingObject> workingObjects = new HashMap<>(); // versioned objects accessed, as this thread sees them
    private final Map<ObjectNumber, WorkingObject> stagedObjects = new HashMap<>(); // ...of which with fields staged
    private final Map<ObjectNumber, WorkingArray> workingArrays = new HashMap<>(); // versioned arrays accessed, as this thread sees them
    private final Map<ObjectNumber, WorkingArray> stagedArrays = new HashMap<>(); // ...of which with elements staged

    // objects with a working object or array, so it can be dropped when they die
    private final Map<ObjectNumber, StagedReference> trackedObjects = new HashMap<>();
    private final ReferenceQueue<StaticObject> deadQueue = new ReferenceQueue<>();

    // --- What the thread sees ---

    /** The thread's value for {@code field} of {@code holder}, or null if it has none yet. */
    Object workingField(StaticObject holder, Field field) {
        WorkingObject w = workingObjects.get(holder.getObjectNumber());
        return w == null ? null : w.values[field.getSlot()];
    }

    /** The storage of the thread's working array for {@code array}, or null if it has none yet. */
    Object workingArray(StaticObject array) {
        WorkingArray working = workingArrays.get(array.getObjectNumber());
        return working == null ? null : working.storage;
    }

    /** Bring {@code value}, from the thread's history, in as its value for {@code field} of {@code holder}, not staged. */
    void addWorkingField(StaticObject holder, Field field, Object value) {
        workingObject(holder, field).values[field.getSlot()] = value;
    }

    /** Bring {@code storage}, built from the thread's history, in as its working array for {@code array}, not staged. */
    void addWorkingArray(StaticObject array, Object storage) {
        workingArrays.put(array.getObjectNumber(), new WorkingArray(storage));
        track(array);
    }

    /** {@code changes} to object {@code number} were pulled, so they replace what the thread sees of it, if it sees it at all. */
    void applyPulled(ObjectNumber number, ObjectChanges changes) {
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
    }

    // --- What the thread has not committed ---

    /** Write {@code value} to {@code field} of {@code holder}, staged until the next commit. */
    void writeField(StaticObject holder, Field field, Object value) {
        WorkingObject w = workingObject(holder, field);
        int slot = field.getSlot();
        w.values[slot] = value;
        // the first staged write to this object puts it among the staged
        if (w.written.isEmpty()) {
            stagedObjects.put(holder.getObjectNumber(), w);
        }
        w.written.set(slot);
        dropDead();
    }

    /**
     * Elements {@code [from, to)} of the thread's working array for {@code array} are written, staged until
     * the next commit. The caller writes the values into the storage itself.
     */
    void writeArray(StaticObject array, int from, int to) {
        WorkingArray working = workingArrays.get(array.getObjectNumber());
        assert working != null : "an array write with no working array";
        working.markWrittenRange(from, to);
        stagedArrays.put(array.getObjectNumber(), working);
    }

    boolean hasStaged() {
        return !stagedObjects.isEmpty() || !stagedArrays.isEmpty();
    }

    Staged stagedCounts() {
        int fields = 0;
        for (WorkingObject w : stagedObjects.values()) {
            fields += w.written.cardinality();
        }
        long elements = 0;
        for (WorkingArray a : stagedArrays.values()) {
            elements += a.written.cardinality();
        }
        return new Staged(fields, stagedArrays.size(), elements);
    }

    /** The staged writes of live objects, copied, per object, as a commit's changes. They stay staged. */
    Map<ObjectNumber, ObjectChanges> stagedChanges() {
        dropDead();
        Map<ObjectNumber, ObjectChanges> changes = new HashMap<>();
        stagedObjects.forEach((number, w) -> changes.put(number, FieldChanges.of(w)));
        stagedArrays.forEach((number, a) -> changes.put(number, ArrayChanges.of(a)));   // copies the bits and values
        return changes;
    }

    /** The slots of the staged fields of object {@code number}, copied, empty if none. */
    BitSet stagedFields(ObjectNumber number) {
        WorkingObject w = stagedObjects.get(number);
        return w == null ? new BitSet() : (BitSet) w.written.clone();
    }

    /** The staged elements of array {@code number}, copied, empty if none. */
    BitSet stagedElements(ObjectNumber number) {
        WorkingArray a = stagedArrays.get(number);
        return a == null ? new BitSet() : (BitSet) a.written.clone();
    }

    /** The fields in {@code slots} of object {@code number} are no longer staged, the thread's values for them left as they are. */
    void unstage(ObjectNumber number, BitSet slots) {
        WorkingObject w = stagedObjects.get(number);
        if (w == null) {
            return;
        }
        w.written.andNot(slots);
        if (w.written.isEmpty()) {
            stagedObjects.remove(number);
        }
    }

    /**
     * The staged writes have been committed, so they are counted for the stats and are no longer staged, the
     * thread's values left as they are. Staging only grows between commits, so its size now is its peak.
     */
    void commitStaged() {
        Staged staged = stagedCounts();
        if (staged.total() > 0) {
            ModelEvents.stagingObserved(staged.total());
            ModelEvents.committed(staged.fields(), staged.arrays(), staged.elements());
        }
        for (WorkingObject w : stagedObjects.values()) {
            w.written.clear();
        }
        stagedObjects.clear();
        for (WorkingArray a : stagedArrays.values()) {
            a.written.clear();
        }
        stagedArrays.clear();
    }

    // --- The whole workspace ---

    /** Drop every working object and array, as when the thread takes another history whole. Nothing may be staged. */
    void clear() {
        assert !hasStaged() : "dropping working objects with staged writes";
        workingObjects.clear();
        workingArrays.clear();
    }

    // Drop the working objects and arrays of objects that have been collected. The model's cleaner marks
    // them dead for every view, so this only tidies this thread's workspace.
    private void dropDead() {
        Reference<?> deadReference;
        while ((deadReference = deadQueue.poll()) != null) {
            ObjectNumber number = ((StagedReference) deadReference).number;
            trackedObjects.remove(number);
            WorkingArray array = workingArrays.remove(number);
            if (array != null) {
                stagedArrays.remove(number);
                ModelEvents.arrayDrained(array.written.cardinality());
            }
            workingObjects.remove(number);
            WorkingObject staged = stagedObjects.remove(number);
            if (staged != null) {
                ModelEvents.stagedFieldsDrained(staged.written.cardinality());
            }
        }
    }

    // the thread's working object for holder, started empty, laid out by field's table, if it has none
    private WorkingObject workingObject(StaticObject holder, Field field) {
        WorkingObject w = workingObjects.get(holder.getObjectNumber());
        if (w == null) {
            w = new WorkingObject(WorkingObject.tableOf(holder, field));
            workingObjects.put(holder.getObjectNumber(), w);
            track(holder);
        }
        return w;
    }

    // track an object with a working object or array, so it can be dropped when the object dies
    private void track(StaticObject object) {
        trackedObjects.computeIfAbsent(object.getObjectNumber(), n -> new StagedReference(object, n, deadQueue));
    }
}
