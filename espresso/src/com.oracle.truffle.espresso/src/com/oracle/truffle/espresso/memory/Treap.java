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

import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

final class Treap<K extends Comparable<K>, V> {

    static final class Node<K extends Comparable<K>, V> {
        final K key;
        final int priority;
        final V value;
        final Node<K, V> left;
        final Node<K, V> right;
        // the keys in this subtree, set by the constructor every transformation goes through
        final int size;

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
            this.size = 1 + size(left) + size(right);
        }

        Node(K key, V value) {
            this(key, value, null, null);
        }

        static int size(Node<?, ?> node) {
            return node == null ? 0 : node.size;
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

        static <K extends Comparable<K>, V> V get(Node<K, V> node, K key) {
            if (node == null) {
                return null;
            }

            int compare = key.compareTo(node.key);

            if (compare == 0) {
                // key == node.key
                return node.value;

            } else if (compare < 0) {
                // key < node.key
                return get(node.left, key);

            } else {
                // key > node.key
                return get(node.right, key);
            }
        }

        // the tree without key, or node itself when it does not hold key
        static <K extends Comparable<K>, V> Node<K, V> remove(Node<K, V> node, K key) {
            if (node == null) {
                return null;
            }

            int compare = key.compareTo(node.key);

            if (compare == 0) {
                // key == node.key, so its children take its place
                return join(node.left, node.right);

            } else if (compare < 0) {
                // key < node.key
                return copyIfChanged(node, remove(node.left, key), node.right, node.value);

            } else {
                // key > node.key
                return copyIfChanged(node, node.left, remove(node.right, key), node.value);
            }
        }

        // the tree without every key matching, or node itself when none match
        static <K extends Comparable<K>, V> Node<K, V> removeIf(Node<K, V> node, Predicate<K> matches, BiConsumer<K, V> removed) {
            if (node == null) {
                return null;
            }

            Node<K, V> left = removeIf(node.left, matches, removed);
            Node<K, V> right = removeIf(node.right, matches, removed);

            if (matches.test(node.key)) {
                // this key goes, so its children, already filtered, take its place
                removed.accept(node.key, node.value);
                return join(left, right);
            }
            return copyIfChanged(node, left, right, node.value);
        }

        // two trees as one, every key of below being less than every key of above, so only priorities are compared
        static <K extends Comparable<K>, V> Node<K, V> join(Node<K, V> below, Node<K, V> above) {
            if (below == null) {
                return above;
            }
            if (above == null) {
                return below;
            }

            if (below.priority > above.priority) {
                // below's root stays on top, and above joins its right side
                return copyIfChanged(below, below.left, join(below.right, above), below.value);
            } else {
                // above's root stays on top, and below joins its left side
                return copyIfChanged(above, join(below, above.left), above.right, above.value);
            }
        }

        // a tree cut at a key, the keys below it and above it, with the key's own value if the tree held it
        record Split<K extends Comparable<K>, V>(Node<K, V> below, V value, Node<K, V> above) {
        }

        // cutting at the root's own key copies nothing, which is what keeps a union of same-shaped trees cheap
        static <K extends Comparable<K>, V> Split<K, V> split(Node<K, V> node, K key) {
            if (node == null) {
                return new Split<>(null, null, null);
            }

            int compare = key.compareTo(node.key);

            if (compare == 0) {
                // key == node.key
                return new Split<>(node.left, node.value, node.right);

            } else if (compare < 0) {
                // key < node.key, so this node and its right subtree are above the key
                Split<K, V> s = split(node.left, key);
                return new Split<>(s.below(), s.value(), copyIfChanged(node, s.above(), node.right, node.value));

            } else {
                // key > node.key, so this node and its left subtree are below the key
                Split<K, V> s = split(node.right, key);
                return new Split<>(copyIfChanged(node, node.left, s.below(), node.value), s.value(), s.above());
            }
        }

        // Mine and theirs together. The higher of the two roots goes on top, the other tree is split at its key,
        // and each side is unioned with the matching side of the split. A key both hold with different values
        // goes to the resolver, and a key only theirs holds is taken.
        static <K extends Comparable<K>, V> Node<K, V> union(Node<K, V> mine, Node<K, V> theirs, Resolver<K, V> resolver) {
            if (mine == theirs || theirs == null) {
                // the same subtree on both sides, kept whole without a look inside, or nothing of theirs here
                return mine;
            }
            if (mine == null) {
                // all of this subtree is theirs alone
                forEach(theirs, resolver::taken);
                return theirs;
            }

            if (mine.priority >= theirs.priority) {
                // my root stays on top, so their tree is split at its key
                Split<K, V> s = split(theirs, mine.key);
                Node<K, V> left = union(mine.left, s.below(), resolver);
                Node<K, V> right = union(mine.right, s.above(), resolver);
                V value = s.value() == null || s.value() == mine.value ? mine.value : resolver.resolve(mine.key, mine.value, s.value());
                // a result equal to their node is their node, so a puller converges on nodes others already hold
                // and later unions with them find the same subtree on both sides
                if (mine.key.compareTo(theirs.key) == 0 && left == theirs.left && right == theirs.right && value == theirs.value) {
                    return theirs;
                }
                return copyIfChanged(mine, left, right, value);

            } else {
                // their root goes on top, so my tree is split at its key
                Split<K, V> s = split(mine, theirs.key);
                Node<K, V> left = union(s.below(), theirs.left, resolver);
                Node<K, V> right = union(s.above(), theirs.right, resolver);
                V value;
                if (s.value() == null) {
                    resolver.taken(theirs.key, theirs.value);
                    value = theirs.value;
                } else {
                    value = s.value() == theirs.value ? theirs.value : resolver.resolve(theirs.key, s.value(), theirs.value);
                }
                return copyIfChanged(theirs, left, right, value);
            }
        }

        // every node of the tree into seen, stopping at one already there, as its subtree is there too
        static void addNodesTo(Node<?, ?> node, Set<Object> seen) {
            if (node == null || !seen.add(node)) {
                return;
            }
            addNodesTo(node.left, seen);
            addNodesTo(node.right, seen);
        }

        static <K extends Comparable<K>, V> void forEach(Node<K, V> node, BiConsumer<K, V> action) {
            if (node == null) {
                return;
            }
            forEach(node.left, action);
            action.accept(node.key, node.value);
            forEach(node.right, action);
        }

        // node with these children and value, or node itself when none changed, so unchanged subtrees stay shared
        static <K extends Comparable<K>, V> Node<K, V> copyIfChanged(Node<K, V> node, Node<K, V> left, Node<K, V> right, V value) {
            if (left == node.left && right == node.right && value == node.value) {
                return node;
            }
            return new Node<>(node.key, value, left, right);
        }
    }

    /** How a union resolves the keys it meets, supplied by the caller, who alone knows what the values mean. */
    interface Resolver<K, V> {
        /** The value to keep for a key both trees hold with different values, mine and theirs. */
        V resolve(K key, V mine, V theirs);

        /** A key only theirs holds, which the union takes as it is. */
        void taken(K key, V theirs);
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

    // every entry put into one new treap, built node by node so only the result is wrapped
    Treap<K, V> putAll(Map<K, V> entries) {
        Node<K, V> result = root;
        for (Map.Entry<K, V> e : entries.entrySet()) {
            result = Node.put(result, e.getKey(), e.getValue());
        }
        return new Treap<>(result);
    }

    V get(K key) {
        return Node.get(root, key);
    }

    // the number of keys this treap holds
    int size() {
        return Node.size(root);
    }

    // every node of this treap into seen, which should compare by identity, so treaps sharing nodes count them once
    void addNodesTo(Set<Object> seen) {
        Node.addNodesTo(root, seen);
    }

    // this treap without key, or this treap itself when it does not hold key
    Treap<K, V> remove(K key) {
        Node<K, V> result = Node.remove(root, key);
        return result == root ? this : new Treap<>(result);
    }

    // this treap without every key matching, each removed entry given to removed, or this treap itself when none match
    Treap<K, V> removeIf(Predicate<K> matches, BiConsumer<K, V> removed) {
        Node<K, V> result = Node.removeIf(root, matches, removed);
        return result == root ? this : new Treap<>(result);
    }

    /** This treap and {@code theirs} together, keeping whatever both share, with {@code resolver} resolving the keys that differ. */
    Treap<K, V> union(Treap<K, V> theirs, Resolver<K, V> resolver) {
        Node<K, V> result = Node.union(root, theirs.root, resolver);
        return result == root ? this : result == theirs.root ? theirs : new Treap<>(result);
    }
}
