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

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.oracle.truffle.espresso.impl.Field;

/**
 * A conflict a synchronisation action found, held on its thread until it is raised as a guest error
 * at the next safe point: the locations both sides wrote, the action, and where in user code it was.
 * The message is built when it is raised.
 */
public final class ConflictReport {

    private final ConflictingLocations locations;
    private final String action;
    private final String userCodeLocation;

    ConflictReport(ConflictingLocations locations, String action, String userCodeLocation) {
        this.locations = locations;
        this.action = action;
        this.userCodeLocation = userCodeLocation;
    }

    public String message() {
        String what = Stream.concat(
                        locations.fields().values().stream().flatMap(Set::stream).map(ConflictReport::describe),
                        locations.arrays().entrySet().stream().map(e -> "array " + e.getKey().threadId + ":" + e.getKey().count + " at indices " + e.getValue()))
                        .sorted().collect(Collectors.joining(", "));
        return "Sync-as-Share conflict: incompatible concurrent update to " + what + ", caught at " + action + " in " + userCodeLocation;
    }

    private static String describe(Field field) {
        return field.getDeclaringKlass().getName() + "." + field.getName();
    }
}
