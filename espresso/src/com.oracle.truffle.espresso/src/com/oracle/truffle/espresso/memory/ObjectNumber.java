package com.oracle.truffle.espresso.memory;

public record ObjectNumber(long thread, long count) {

    public static ObjectNumber objectNumberForThread(long threadId) {
        return new ObjectNumber(threadId, 0);
    }

    public ObjectNumber next() {
        return new ObjectNumber(thread, count + 1);
    }
}