package com.tuv.fanout.capacity;

/** One slot of downstream capacity. Closing it more than once has no further effect. */
public interface Permit extends AutoCloseable {

    @Override
    void close();
}
