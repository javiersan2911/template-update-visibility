package com.tuv.fanout.support;

import com.tuv.fanout.capacity.CapacityFeedback;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

public final class RecordingFeedback implements CapacityFeedback {

    private final AtomicInteger successes = new AtomicInteger();
    private final AtomicInteger overloads = new AtomicInteger();

    @Override
    public void recordSuccess() {
        successes.incrementAndGet();
    }

    @Override
    public void recordOverload(Duration pause) {
        overloads.incrementAndGet();
    }

    public int successes() {
        return successes.get();
    }

    public int overloads() {
        return overloads.get();
    }
}
