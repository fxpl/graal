/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.espresso.threads;

import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.EspressoException;
import com.oracle.truffle.espresso.runtime.EspressoExitException;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.scheduler.SyncOp;

/**
 * The payload of the host thread executing a guest {@linkplain #thread thread}.
 * 
 * When started, the host thread:
 * <ul>
 * <li>Attached itself to the context.</li>
 * <li>Fetches the guest {@link Thread#run()} methods and executes it.</li>
 * <li>If an exception escapes the guest thread, call
 * {@code Thread#dispatchUncaughtException(Throwable)}.</li>
 * <li>Upon completion, {@link ThreadAccess#terminate(StaticObject, DirectCallNode) terminates} the
 * guest thread.</li>
 * </ul>
 */
final class GuestRunnable implements Runnable {
    private final EspressoContext context;
    private final StaticObject thread;
    private final DirectCallNode exit;
    private final DirectCallNode dispatchUncaught;

    GuestRunnable(EspressoContext context, StaticObject thread, DirectCallNode exit, DirectCallNode dispatchUncaught) {
        this.context = context;
        this.thread = thread;
        this.exit = exit;
        this.dispatchUncaught = dispatchUncaught;
    }

    @Override
    public void run() {
        try {
            context.registerCurrentThread(thread);
            context.getVM().attachThread(Thread.currentThread());
            try {
                // Sync-as-Share: thread start = pull g into this thread's view (cannot conflict).
                if (context.isVersionedMemoryEnabled()) {
                    context.getMemoryModel().onThreadStart();
                }
                context.seam(SyncOp.THREAD_START, thread);
                // Execute the payload
                context.getThreadAccess().checkDeprecatedThreadStatus(thread);
                context.getMeta().java_lang_Thread_run.invokeDirectVirtual(thread);
                context.getThreadAccess().checkDeprecatedThreadStatus(thread);
                context.seam(SyncOp.THREAD_END, thread);
                // Sync-as-Share: thread end = push this thread's view to g.
                if (context.isVersionedMemoryEnabled()) {
                    context.getMemoryModel().onThreadEnd();
                    // A conflict in the thread's final push has no active guest frame to catch it, so
                    // raise it as an uncaught exception on this terminating thread (dispatched below).
                    // Paper §4.5 leaves recover-vs-terminate open; we terminate.
                    if (context.getLanguage().getThreadLocalState().getMemoryView().hasPendingConflict()) {
                        String msg = context.getLanguage().getThreadLocalState().getMemoryView().takePendingConflict();
                        throw context.getMeta().throwExceptionWithMessage(context.getMeta().java_lang_Error, msg);
                    }
                }
            } catch (EspressoException uncaught) {
                dispatchUncaught.call(thread, uncaught.getGuestException());
            }
        } catch (EspressoExitException exitException) {
            /* Suppress */
        } finally {
            context.getThreadAccess().terminate(thread, exit);
            if (context.isClosing()) {
                // Ignore exceptions that arise during closing.
                return;
            }
        }
    }
}
