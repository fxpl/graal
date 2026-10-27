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

import java.lang.ref.Cleaner;
import java.util.concurrent.ConcurrentHashMap;

import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

public final class ObjectSyncState {

  final SyncState lock = new SyncState();
  final ConcurrentHashMap<Field, SyncState> volatiles = new ConcurrentHashMap<>();

  private ObjectSyncState() {}

  /** A fresh holder for {@code owner}; {@code cleaner} releases every commit it holds once {@code owner} dies. */
  public static ObjectSyncState createFor(StaticObject owner, Cleaner cleaner) {
      ObjectSyncState state = new ObjectSyncState();
      cleaner.register(owner, state::releaseSyncStates);
      return state;
  }

  private static void release(SyncState s) {
      if (s != null && s.commit != null) {
          s.commit.decRef();
      }
  }

  private void releaseSyncStates() {
      release(lock);
      volatiles.values().forEach(ObjectSyncState::release);
  }

  public SyncState getLockSyncState() {
      return lock;
  }

  public SyncState getVolatileSyncState(Field f) {
      // only run the construction of a new SyncState if there is nothing there
      return volatiles.computeIfAbsent(f, k -> new SyncState());
  }

}