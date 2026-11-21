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

final class Treap<K extends Comparable<K>, V> {

    static final class Node<K extends Comparable<K>, V> {
        final K key;
        final int priority;
        final V value;
        final Node<K, V> left;
        final Node<K, V> right;

        // Stafford's variant 4 of the MurmurHash3 64-bit finaliser, upper 32 bits, as in java.util.SplittableRandom.mix32
        private static int mix32(long z) {
            z = (z ^ (z >>> 33)) * 0x62a9d9ed799705f5L;
            return (int) (((z ^ (z >>> 28)) * 0xcb24d0a5c88c35b3L) >>> 32);
        }

        Node(K key, V value, Node<K, V> left, Node<K, V> right) {
            this.key = key;
            this.priority = mix32(key.hashCode());
            this.value = value;
            this.left = left;
            this.right = right;
        }

        Node(K key, V value) {
            this(key, value, null, null);
        }

        static <K extends Comparable<K>, V> Node<K, V> put(Node<K, V> node, K key, V value) {
            if (node == null) {
                return new Node<>(key, value);
            }

            int compare = key.compareTo(node.key);

            if (compare == 0) {
                // key == node.key
                return new Node<>(key, value, node.left, node.right);

            } else if (compare < 0) {
                // key < node.key
                Node<K, V> newLeft = put(node.left, key, value);
                if (newLeft.priority > node.priority) {
                    // ROTATE RIGHT
                    Node<K, V> lowered = new Node<>(node.key, node.value, newLeft.right, node.right);
                    return new Node<>(newLeft.key, newLeft.value, newLeft.left, lowered);
                } else {
                    return new Node<>(node.key, node.value, newLeft, node.right);
                }

            } else {
                // key > node.key
                Node<K, V> newRight = put(node.right, key, value);
                if (newRight.priority > node.priority) {
                    // ROTATE LEFT
                    Node<K, V> lowered = new Node<>(node.key, node.value, node.left, newRight.left);
                    return new Node<>(newRight.key, newRight.value, lowered, newRight.right);
                } else {
                    return new Node<>(node.key, node.value, node.left, newRight);
                }
            }
        }
    }

    private final Node<K, V> root;

    private Treap(Node<K, V> root) {
        this.root = root;
    }

    private Treap(K key, V value, Node<K, V> left, Node<K, V> right) {
        this(new Node<K, V>(key, value, left, right));
    }

    Treap(K key, V value) {
        this(new Node<K, V>(key, value));
    }

    static <K extends Comparable<K>, V> Treap<K, V> empty() {
        return new Treap<>(null);
    }

    Treap<K, V> put(K key, V value) {
        return new Treap<>(Node.put(root, key, value));
    }
}
