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

    // the number of objects this snapshot holds changes for, dead ones included until a purge
    int size() {
        return objects.size();
    }

    // this snapshot's treap nodes into seen, for the report of how much snapshots share
    void addNodesTo(Set<Object> seen) {
        objects.addNodesTo(seen);
    }

    // this snapshot without the changes of objects that have died, or this snapshot itself when none have
    Snapshot withoutDead() {
        // counted as Commit.purgeObjects counts them, field writes and whole arrays
        int[] fieldWrites = {0};
        int[] arrays = {0};
        Treap<ObjectNumber, StampedChanges> live = objects.removeIf(ObjectNumber::isDead, (number, changes) -> {
            if (changes instanceof StampedFieldChanges f) {
                fieldWrites[0] += f.writes().size();
            } else {
                arrays[0]++;
            }
        });
        ModelEvents.purged(fieldWrites[0], arrays[0]);
        return live == objects ? this : new Snapshot(live);
    }

    // this snapshot with one commit's changes, stamped with that commit's epoch, as a new snapshot sharing
    // everything they leave alone
    Snapshot withChangesAt(Map<ObjectNumber, ObjectChanges> changes, Epoch epoch) {
        Map<ObjectNumber, StampedChanges> updated = HashMap.newHashMap(changes.size());
        for (Map.Entry<ObjectNumber, ObjectChanges> e : changes.entrySet()) {
            StampedChanges stamped = stamp(e.getValue(), epoch);
            StampedChanges older = objects.get(e.getKey());
            // the fields written in earlier commits are kept, and the older changes, which other snapshots may
            // share, are left as they are
            updated.put(e.getKey(), older == null ? stamped : older.updateWith(stamped));
        }
        return new Snapshot(objects.putAll(updated));
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

    // the merged snapshot, the conflicting locations, the values taken from their side for the workspace, and for
    // the stats the objects both sides held that were resolved, and the objects only theirs held
    record Merged(Snapshot snapshot, ConflictingLocations conflicts, Map<ObjectNumber, ObjectChanges> taken, int resolved, int theirsOnly) {
        boolean hasConflicts() {
            return !conflicts.isEmpty();
        }
    }

    Merged merge(Snapshot theirs, VectorClock myClock, VectorClock theirClock) {
        ClockResolver resolver = new ClockResolver(myClock, theirClock);
        Treap<ObjectNumber, StampedChanges> merged = objects.union(theirs.objects, resolver);

        // on a conflict nothing is merged, so this snapshot stays as it is
        if (resolver.hasConflicts()) {
            return new Merged(this, new ConflictingLocations(resolver.fieldConflicts, resolver.arrayConflicts), Map.of(),
                            resolver.resolved, resolver.theirsOnly);
        }

        // a result the same as either side is that side's snapshot, so the next pull finds them the same
        Snapshot snapshot = merged == objects ? this : merged == theirs.objects ? theirs : new Snapshot(merged);
        return new Merged(snapshot, ConflictingLocations.NONE, resolver.taken, resolver.resolved, resolver.theirsOnly);
    }

    // resolves each object both snapshots hold, location by location, by the two clocks
    private static final class ClockResolver implements Treap.Resolver<ObjectNumber, StampedChanges> {
        private final VectorClock myClock;
        private final VectorClock theirClock;
        final Map<ObjectNumber, Set<Field>> fieldConflicts = new HashMap<>();
        final Map<ObjectNumber, BitSet> arrayConflicts = new HashMap<>();
        // the values taken from their side, which the puller's working copies then take too
        final Map<ObjectNumber, ObjectChanges> taken = new HashMap<>();
        // for the stats, the objects resolved and the objects taken whole
        int resolved;
        int theirsOnly;

        ClockResolver(VectorClock myClock, VectorClock theirClock) {
            this.myClock = myClock;
            this.theirClock = theirClock;
        }

        boolean hasConflicts() {
            return !fieldConflicts.isEmpty() || !arrayConflicts.isEmpty();
        }

        @Override
        public StampedChanges resolve(ObjectNumber number, StampedChanges mine, StampedChanges theirs) {
            resolved++;
            if (mine instanceof StampedFieldChanges m && theirs instanceof StampedFieldChanges t) {
                return resolveFields(number, m, t);
            }
            if (mine instanceof StampedArrayChanges m && theirs instanceof StampedArrayChanges t) {
                return resolveElements(number, m, t);
            }
            throw EspressoError.shouldNotReachHere("an object's changes are always of one kind");
        }

        private StampedChanges resolveFields(ObjectNumber number, StampedFieldChanges m, StampedFieldChanges t) {
            Map<Field, Write> mine = m.writes();
            // only made once something is taken
            Map<Field, Object> takenValues = null;
            // whether every field so far comes out as theirs, and how many of my fields they also wrote
            boolean isAllTheirs = true;
            int alsoMine = 0;
            boolean isConflicted = false;
            for (Map.Entry<Field, Write> e : t.writes().entrySet()) {
                Field field = e.getKey();
                Write theirWrite = e.getValue();
                Write myWrite = mine.get(field);
                if (myWrite != null) {
                    alsoMine++;
                }
                if (myWrite == null) {
                    // only they wrote it, so it is taken
                } else if (myWrite.equals(theirWrite)) {
                    // the same write, which is as much theirs as mine
                    continue;
                } else if (myClock.hasSeen(theirWrite.epoch())) {
                    // I have seen theirs, so mine is newer and the result is not all theirs
                    isAllTheirs = false;
                    continue;
                } else if (!theirClock.hasSeen(myWrite.epoch()) && !field.isResolvedByTheirs()) {
                    // neither has seen the other's write
                    fieldConflicts.computeIfAbsent(number, n -> new HashSet<>()).add(field);
                    isConflicted = true;
                    continue;
                }
                // theirs is newer, or the field takes theirs regardless
                if (takenValues == null) {
                    takenValues = new HashMap<>();
                }
                takenValues.put(field, theirWrite.value());
            }

            // a conflict throws the merge away, so there is nothing to build
            if (isConflicted) {
                return m;
            }
            if (takenValues != null) {
                taken.put(number, FieldChanges.of(takenValues));
            }
            // all theirs, with no field only I wrote, is their changes, so the union can keep their node
            if (isAllTheirs && alsoMine == mine.size()) {
                return t;
            }
            // nothing taken is my changes
            if (takenValues == null) {
                return m;
            }
            // a mix, so only now built, my writes with the taken ones over them
            Map<Field, Write> result = new HashMap<>(mine);
            for (Field field : takenValues.keySet()) {
                result.put(field, t.get(field));
            }
            return StampedFieldChanges.of(result);
        }

        private StampedChanges resolveElements(ObjectNumber number, StampedArrayChanges m, StampedArrayChanges t) {
            Epoch[] mine = m.epochs();
            Epoch[] theirs = t.epochs();
            // only made once something is taken
            Object takenValues = null;
            BitSet takenElements = null;
            // whether every element so far comes out as theirs
            boolean isAllTheirs = true;
            boolean isConflicted = false;
            for (int i = 0; i < mine.length; i++) {
                Epoch myEpoch = mine[i];
                Epoch theirEpoch = theirs[i];
                if (theirEpoch == null) {
                    // they never wrote it, so an element only I wrote is not theirs
                    isAllTheirs &= myEpoch == null;
                    continue;
                }
                if (myEpoch != null && myEpoch.equals(theirEpoch)) {
                    // the same write, which is as much theirs as mine
                    continue;
                }
                if (myEpoch != null && myClock.hasSeen(theirEpoch)) {
                    // I have seen theirs, so mine is newer and the result is not all theirs
                    isAllTheirs = false;
                    continue;
                }
                if (myEpoch != null && !theirClock.hasSeen(myEpoch)) {
                    // neither has seen the other's write
                    arrayConflicts.computeIfAbsent(number, n -> new BitSet()).set(i);
                    isConflicted = true;
                    continue;
                }
                // only they wrote it, or they have seen mine, so theirs is newer
                if (takenValues == null) {
                    takenValues = Array.newInstance(t.values().getClass().getComponentType(), theirs.length);
                    takenElements = new BitSet();
                }
                System.arraycopy(t.values(), i, takenValues, i, 1);
                takenElements.set(i);
            }

            // a conflict throws the merge away, so there is nothing to build
            if (isConflicted) {
                return m;
            }
            if (takenValues != null) {
                taken.put(number, ArrayChanges.of(takenValues, takenElements));
            }
            // every element as theirs is their changes, so the union can keep their node
            if (isAllTheirs) {
                return t;
            }
            // nothing taken is my changes
            if (takenValues == null) {
                return m;
            }
            // a mix, so only now built, my elements with the taken ones over them
            Object values = copyOf(m.values());
            Epoch[] epochs = mine.clone();
            for (int i = takenElements.nextSetBit(0); i >= 0; i = takenElements.nextSetBit(i + 1)) {
                epochs[i] = theirs[i];
                System.arraycopy(t.values(), i, values, i, 1);
            }
            return StampedArrayChanges.of(values, epochs);
        }

        @Override
        public void taken(ObjectNumber number, StampedChanges theirs) {
            theirsOnly++;
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
