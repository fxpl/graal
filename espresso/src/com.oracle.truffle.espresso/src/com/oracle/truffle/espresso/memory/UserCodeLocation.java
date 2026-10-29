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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstanceVisitor;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.nodes.EspressoRootNode;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.vm.VM;

/**
 * Where in the user's program the current thread is, so a diagnostic (a conflict message, a schedule
 * trace event) can point at the user's own code. Deliberately independent of {@link Versioning}: what
 * counts as a user frame here only picks which line a message points at, and must not move when the
 * versioning boundary does.
 */
public final class UserCodeLocation {

    private UserCodeLocation() {
    }

    /**
     * The topmost guest frame as {@code Class.method:line} (the immediate mechanism) and, when that frame
     * is JDK code, also the nearest user frame as the actionable location, e.g. {@code
     * java/lang/Thread.join:1875 (from CatchConflict.main:52)}. When the top frame is already user code
     * the two coincide and only one is shown. {@code "?"} if there is no guest frame. Walks the Truffle
     * stack on the current thread; call synchronously while the frames of interest are live.
     */
    @TruffleBoundary
    public static String current(EspressoContext context) {
        String[] top = {null};
        String user = Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<String>() {
            @Override
            public String visitFrame(FrameInstance frame) {
                EspressoRootNode root = VM.getEspressoRootFromFrame(frame, context);
                if (root == null) {
                    return null;
                }
                String where = formatFrame(root, frame);
                if (top[0] == null) {
                    top[0] = where;
                }
                if (isUserFrame(root.getMethod())) {
                    return where;   // nearest user frame — stop walking
                }
                return null;
            }
        });
        if (top[0] == null) {
            return "?";
        }
        if (user == null || user.equals(top[0])) {
            return top[0];
        }
        return top[0] + " (from " + user + ")";
    }

    /** A user frame is a method declared by a class from a non-boot class loader. */
    private static boolean isUserFrame(Method m) {
        return !StaticObject.isNull(m.getDeclaringKlass().getDefiningClassLoader());
    }

    private static String formatFrame(EspressoRootNode root, FrameInstance frame) {
        Method m = root.getMethod();
        String where = m.getDeclaringKlass().getNameAsString() + "." + m.getNameAsString();
        try {
            int line = m.bciToLineNumber(root.readBCI(frame.getFrame(FrameInstance.FrameAccess.READ_ONLY)));
            if (line > 0) {
                where += ":" + line;
            }
        } catch (RuntimeException ignored) {
            // no line table / no bytecode frame — method name is enough
        }
        return where;
    }
}
