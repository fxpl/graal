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

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.memory.StampedChanges.Write;
import com.oracle.truffle.espresso.meta.EspressoError;

final class Snapshot {

    // the snapshot of nothing written, which every snapshot grows from
    static final Snapshot EMPTY = new Snapshot(Treap.empty());

    private final Treap<ObjectNumber, StampedChanges> objects;

    private Snapshot(Treap<ObjectNumber, StampedChanges> objects) {
        this.objects = objects;
    }

    /** The changes this snapshot holds for object {@code number}, or null if it holds none. */
    StampedChanges get(ObjectNumber number) {
        return objects.get(number);
    }

    /**
     * This snapshot with {@code changes}, one commit's writes per object, folded in and stamped with
     * {@code epoch}, the epoch of that commit, as a new snapshot that shares everything they leave alone. An
     * object's new changes are folded over what this snapshot holds for it, so fields written in earlier
     * commits are kept.
     */
    Snapshot withChanges(Map<ObjectNumber, ObjectChanges> changes, Epoch epoch) {
        // each object appears once in a commit, so its older changes are the ones this snapshot holds
        Map<ObjectNumber, StampedChanges> folded = HashMap.newHashMap(changes.size());
        for (Map.Entry<ObjectNumber, ObjectChanges> e : changes.entrySet()) {
            StampedChanges stamped = stamp(e.getValue(), epoch);
            StampedChanges older = objects.get(e.getKey());
            // a fold makes new changes, so the older ones, which other snapshots may share, are left as they are
            folded.put(e.getKey(), older == null ? stamped : stamped.createFoldWithOlder(older));
        }
        return new Snapshot(objects.putAll(folded));
    }

    // a commit's changes to one object, each write stamped with the commit's epoch
    private static StampedChanges stamp(ObjectChanges changes, Epoch epoch) {
        if (changes instanceof FieldChanges f) {
            return StampedFieldChanges.of(f, epoch);
        }
        if (changes instanceof ArrayChanges a) {
            return StampedArrayChanges.of(a, epoch);
        }
        throw EspressoError.shouldNotReachHere("changes are of fields or of elements");
    }

    // the merged snapshot, the conflicting locations, and the values taken from their side for the workspace
    record Merged(Snapshot snapshot, ConflictingLocations conflicts, Map<ObjectNumber, ObjectChanges> taken) {
        boolean hasConflicts() {
            return !conflicts.isEmpty();
        }
    }

    Merged merge(Snapshot theirs, VectorClock myClock, VectorClock theirClock) {
        ClockResolver resolver = new ClockResolver(myClock, theirClock);
        Treap<ObjectNumber, StampedChanges> merged = objects.union(theirs.objects, resolver);

        // on a conflict nothing is merged, so this snapshot stays as it is
        if (resolver.hasConflicts()) {
            return new Merged(this, new ConflictingLocations(resolver.fieldConflicts, resolver.arrayConflicts), Map.of());
        }

        // a result the same as either side is that side's snapshot, so the next pull finds them the same
        Snapshot snapshot = merged == objects ? this : merged == theirs.objects ? theirs : new Snapshot(merged);
        return new Merged(snapshot, ConflictingLocations.NONE, resolver.taken);
    }

    // settles each object both snapshots hold, location by location, by the two clocks
    private static final class ClockResolver implements Treap.Resolver<ObjectNumber, StampedChanges> {
        private final VectorClock myClock;
        private final VectorClock theirClock;
        final Map<ObjectNumber, Set<Field>> fieldConflicts = new HashMap<>();
        final Map<ObjectNumber, BitSet> arrayConflicts = new HashMap<>();
        // the values taken from their side, which the puller's working copies then take too
        final Map<ObjectNumber, ObjectChanges> taken = new HashMap<>();

        ClockResolver(VectorClock myClock, VectorClock theirClock) {
            this.myClock = myClock;
            this.theirClock = theirClock;
        }

        boolean hasConflicts() {
            return !fieldConflicts.isEmpty() || !arrayConflicts.isEmpty();
        }

        @Override
        public StampedChanges resolve(ObjectNumber number, StampedChanges mine, StampedChanges theirs) {
            if (mine instanceof StampedFieldChanges m && theirs instanceof StampedFieldChanges t) {
                return resolveFields(number, m, t);
            }
            if (mine instanceof StampedArrayChanges m && theirs instanceof StampedArrayChanges t) {
                return resolveElements(number, m, t);
            }
            throw EspressoError.shouldNotReachHere("an object's changes are always of one kind");
        }

        private StampedChanges resolveFields(ObjectNumber number, StampedFieldChanges m, StampedFieldChanges t) {
            Map<Field, Write> result = new HashMap<>(m.writes());
            Map<Field, Object> takenValues = new HashMap<>();
            for (Map.Entry<Field, Write> e : t.writes().entrySet()) {
                Field field = e.getKey();
                Write theirWrite = e.getValue();
                Write myWrite = result.get(field);
                if (myWrite == null) {
                    // only they wrote it
                    result.put(field, theirWrite);
                    takenValues.put(field, theirWrite.value());
                } else if (myWrite.equals(theirWrite) || myClock.hasSeen(theirWrite.epoch())) {
                    // the same write, or I have seen theirs, so mine is newer
                    continue;
                } else if (theirClock.hasSeen(myWrite.epoch()) || field.isResolvedByTheirs()) {
                    // they have seen mine, so theirs is newer, or the field takes theirs regardless
                    result.put(field, theirWrite);
                    takenValues.put(field, theirWrite.value());
                } else {
                    // neither has seen the other's write
                    fieldConflicts.computeIfAbsent(number, n -> new HashSet<>()).add(field);
                }
            }
            if (!takenValues.isEmpty()) {
                taken.put(number, FieldChanges.of(takenValues));
            }
            // the same writes as one side is that side's changes, so the union can keep its node
            if (result.equals(t.writes())) {
                return t;
            }
            return result.equals(m.writes()) ? m : StampedFieldChanges.of(result);
        }

        private StampedChanges resolveElements(ObjectNumber number, StampedArrayChanges m, StampedArrayChanges t) {
            Object values = copyOf(m.values());
            Epoch[] epochs = m.epochs().clone();
            Object takenValues = Array.newInstance(values.getClass().getComponentType(), epochs.length);
            BitSet takenElements = new BitSet();
            for (int i = 0; i < epochs.length; i++) {
                Epoch myEpoch = epochs[i];
                Epoch theirEpoch = t.epochs()[i];
                if (theirEpoch == null || (myEpoch != null && (myEpoch.equals(theirEpoch) || myClock.hasSeen(theirEpoch)))) {
                    // they never wrote it, or it is the same write, or I have seen theirs, so mine stands
                    continue;
                }
                if (myEpoch != null && !theirClock.hasSeen(myEpoch)) {
                    // neither has seen the other's write
                    arrayConflicts.computeIfAbsent(number, n -> new BitSet()).set(i);
                    continue;
                }
                // only they wrote it, or they have seen mine, so theirs is newer
                epochs[i] = theirEpoch;
                System.arraycopy(t.values(), i, values, i, 1);
                System.arraycopy(t.values(), i, takenValues, i, 1);
                takenElements.set(i);
            }
            if (!takenElements.isEmpty()) {
                taken.put(number, ArrayChanges.of(takenValues, takenElements));
            }
            // an epoch names one commit, which wrote one value, so the same epochs as one side is that side's changes
            if (Arrays.equals(epochs, t.epochs())) {
                return t;
            }
            return Arrays.equals(epochs, m.epochs()) ? m : StampedArrayChanges.of(values, epochs);
        }

        @Override
        public void taken(ObjectNumber number, StampedChanges theirs) {
            if (theirs instanceof StampedFieldChanges t) {
                Map<Field, Object> values = HashMap.newHashMap(t.writes().size());
                t.writes().forEach((field, write) -> values.put(field, write.value()));
                taken.put(number, FieldChanges.of(values));
            } else if (theirs instanceof StampedArrayChanges t) {
                BitSet written = new BitSet();
                for (int i = 0; i < t.epochs().length; i++) {
                    if (t.epochs()[i] != null) {
                        written.set(i);
                    }
                }
                // a copy, as array changes can be modified and the snapshot's values cannot
                taken.put(number, ArrayChanges.of(copyOf(t.values()), written));
            }
        }

        // a copy of an array of any component type, primitives unboxed
        private static Object copyOf(Object array) {
            int length = Array.getLength(array);
            Object copy = Array.newInstance(array.getClass().getComponentType(), length);
            System.arraycopy(array, 0, copy, 0, length);
            return copy;
        }
    }

}
