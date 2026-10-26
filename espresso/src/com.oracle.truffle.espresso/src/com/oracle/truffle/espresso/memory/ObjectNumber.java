package com.oracle.truffle.espresso.memory;

public class ObjectNumber {
    final long threadId;
    final long count;
    volatile boolean dead;

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
}