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

/**
 * The branching model's per-lock synchronisation state (the prototype's {@code branching::LockState}):
 * the commit a lock currently publishes — the tip of the "lock branch" — together with the publisher's
 * {@link VectorClock} frontier at publication (the analogue of FastTrack's per-lock clock {@code L_m}).
 * Acquiring merges this into the thread and republishes; releasing republishes the thread's tip.
 * {@code commit}/{@code clock} are {@code null} until first published. Held in a side map keyed by the
 * monitor object; accessed only under that monitor, so it needs no internal synchronisation. (Linear
 * has no per-lock state — communication is the single global {@code g} — which is why the Linear model
 * returns no lock state.)
 */
public final class LockState {
    Commit commit;
    VectorClock clock;
}
