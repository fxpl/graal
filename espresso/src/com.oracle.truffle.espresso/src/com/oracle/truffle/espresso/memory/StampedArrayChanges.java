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

import com.oracle.truffle.espresso.meta.EspressoError;

/** An array's latest element writes in a {@link Snapshot}, each stamped with the epoch of the commit that made it. */
final class StampedArrayChanges implements StampedChanges {

    // the written elements' values, the rest left at their defaults
    private final Object values;
    // the epoch of each element's latest write, null where the element was never written
    private final Epoch[] epochs;

    private StampedArrayChanges(Object values, Epoch[] epochs) {
        this.values = values;
        this.epochs = epochs;
    }

    /** The written elements of {@code changes}, each stamped with {@code epoch}, the epoch of the commit making them. */
    static StampedArrayChanges of(ArrayChanges changes, Epoch epoch) {
        int length = Array.getLength(changes.changes);
        Object values = Array.newInstance(changes.changes.getClass().getComponentType(), length);
        Epoch[] epochs = new Epoch[length];
        BitSet written = changes.written();
        for (int i = written.nextSetBit(0); i >= 0; i = written.nextSetBit(i + 1)) {
            epochs[i] = epoch;
        }
        copyWritten(epochs, changes.changes, values);
        return new StampedArrayChanges(values, epochs);
    }

    /** Elements already chosen, each keeping its own epoch, as when a merge takes some from each side. Takes ownership of both arrays. */
    static StampedArrayChanges of(Object values, Epoch[] epochs) {
        return new StampedArrayChanges(values, epochs);
    }

    // copies each run of written elements, so a primitive array is never boxed
    private static void copyWritten(Epoch[] epochs, Object src, Object dst) {
        int from = 0;
        while (from < epochs.length) {
            // skip to the next written element
            while (from < epochs.length && epochs[from] == null) {
                from++;
            }
            // then to the end of its run
            int to = from;
            while (to < epochs.length && epochs[to] != null) {
                to++;
            }
            System.arraycopy(src, from, dst, from, to - from);
            from = to;
        }
    }

    /** Copy the written elements into {@code storage}, a working array of the same length. */
    void applyTo(Object storage) {
        copyWritten(epochs, values, storage);
    }

    /** The written elements' values, which must not be modified. */
    Object values() {
        return values;
    }

    /** Each element's latest write epoch, null where unwritten, which must not be modified. */
    Epoch[] epochs() {
        return epochs;
    }

    @Override
    public StampedChanges createFoldWithOlder(StampedChanges older) {
        if (!(older instanceof StampedArrayChanges o)) {
            throw EspressoError.shouldNotReachHere("an object's changes are always of one kind");
        }
        Object folded = Array.newInstance(values.getClass().getComponentType(), epochs.length);
        // the older writes first, then these over them
        copyWritten(o.epochs, o.values, folded);
        copyWritten(epochs, values, folded);
        Epoch[] foldedEpochs = o.epochs.clone();
        for (int i = 0; i < epochs.length; i++) {
            if (epochs[i] != null) {
                foldedEpochs[i] = epochs[i];
            }
        }
        return new StampedArrayChanges(folded, foldedEpochs);
    }
}
