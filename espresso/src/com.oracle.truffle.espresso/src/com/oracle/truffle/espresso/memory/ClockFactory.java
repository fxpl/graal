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

import com.oracle.truffle.espresso.EspressoOptions;

/**
 * Makes each thread's first clock. A model owns one for all its threads, as some clocks share state
 * across threads (which entries of an accordion clock are free).
 */
public interface ClockFactory {

    /** The clock of thread {@code thread} before its first event. */
    Clock start(int thread);

    /** The factory {@code --java.SyncShareClock} selects. */
    static ClockFactory of(EspressoOptions.SyncShareClockMode mode) {
        switch (mode) {
            case EPOCH:
                return Epoch::start;
            case VECTOR:
                return VectorClock::start;
            default:
                throw new IllegalArgumentException("unknown clock: " + mode);
        }
    }
}
