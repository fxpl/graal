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

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * A view's cache of committed values under its current head, so a read walks the commit history at
 * most once per location. A location is cached with its value, or with {@link MemoryModel#ABSENT}
 * when no commit wrote it. The view evicts a location when a merge brings in another thread's write
 * to it, and when its object dies.
 */
final class ReadCache {

    // past this many entries the cache is cleared; hot locations come back with one walk each
    private static final int CAPACITY = 1 << 20;

    private final Map<FieldLocation, Object> entries = new HashMap<>();

    /** The cached value, {@link MemoryModel#ABSENT} if cached as never committed, or null if not cached. */
    Object get(FieldLocation loc) {
        Object value = entries.get(loc);
        if (value != null) {
            VersioningStats.onReadCacheHit();
        }
        return value;
    }

    void put(FieldLocation loc, Object value) {
        if (entries.size() >= CAPACITY) {
            clear();
        }
        entries.put(loc, value);
    }

    /** The changes this view just committed: the head now says exactly these values. */
    void putAll(Map<FieldLocation, Object> committed) {
        if (entries.size() + committed.size() > CAPACITY) {
            clear();
        }
        entries.putAll(committed);
    }

    void evict(FieldLocation loc) {
        entries.remove(loc);
    }

    void evictAll(Collection<FieldLocation> locs) {
        for (FieldLocation loc : locs) {
            entries.remove(loc);
        }
    }

    private void clear() {
        entries.clear();
        VersioningStats.onReadCacheClear();
    }
}
