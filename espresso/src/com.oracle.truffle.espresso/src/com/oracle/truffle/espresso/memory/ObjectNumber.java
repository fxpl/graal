package com.oracle.truffle.espresso.memory;

import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.impl.Klass;

public class ObjectNumber {
    final long threadId;
    final long count;
    volatile boolean dead;

    // what the object is for reports, set when it is first shared, sharedThrough null when no field is known
    record Description(Klass type, Field sharedThrough) {
    }

    private volatile Description description;

    private ObjectNumber(long threadId, long count) {
        this.threadId = threadId;
        this.count = count;
        this.dead = false;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof ObjectNumber n) {
            return threadId == n.threadId && count == n.count;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(threadId) + Long.hashCode(count);
    }

    public static ObjectNumber objectNumberForThread(long threadId) {
        return new ObjectNumber(threadId, 0);
    }

    public ObjectNumber next() {
        return new ObjectNumber(threadId, count + 1);
    }

    public void setDead() {
        dead = true;
    }

    public boolean isDead() {
        return dead;
    }

    /** The object is being shared for the first time, as a {@code type}, through {@code sharedThrough} if not null. */
    void onShared(Klass type, Field sharedThrough) {
        if (description == null) {
            description = new Description(type, sharedThrough);
        }
    }

    // the object's type, its number, and the field it was shared through, as much as is known
    @Override
    public String toString() {
        Description d = description;
        String number = threadId + ":" + count;
        if (d == null) {
            return number;
        }
        String through = d.sharedThrough() == null ? ""
                        : " via " + d.sharedThrough().getDeclaringKlass().getName() + "." + d.sharedThrough().getName();
        // getName is null for an array class, which has only a type descriptor
        return d.type().getNameAsString() + " " + number + through;
    }
}