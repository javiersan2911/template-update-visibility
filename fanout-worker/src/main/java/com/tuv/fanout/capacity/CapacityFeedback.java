package com.tuv.fanout.capacity;

import java.time.Duration;

/** Health signals from the downstream system, used to adapt the concurrency limit. */
public interface CapacityFeedback {

    void recordSuccess();

    /**
     * @param pause how long the downstream system asked callers to stay away; zero when it
     *              gave no hint. No new work is admitted until the pause has passed.
     */
    void recordOverload(Duration pause);
}
