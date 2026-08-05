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
package com.oracle.truffle.espresso.nodes.commands;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * Special eval command the launcher fires immediately before invoking the guest {@code main} method,
 * once the main class has been loaded and linked. It marks the boundary between VM/launcher setup and
 * user code: the schedule explorer starts delivering seam observations here, so a programmer sees only
 * seams from {@code main} onward, not the class-loading and reflective-launch machinery before it.
 *
 * <p>Independent of the memory model, which keeps its own earlier activation (at
 * {@code EspressoContext.activate}) for version-0 semantics.
 */
public final class BeginUserCodeNode extends RootNode {
    public static final String EVAL_NAME = "<begin-user-code>";

    public BeginUserCodeNode(TruffleLanguage<?> language) {
        super(language);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        EspressoContext.get(this).getScheduler().activate();
        return StaticObject.NULL;
    }

    @Override
    public String getName() {
        return EVAL_NAME;
    }
}
