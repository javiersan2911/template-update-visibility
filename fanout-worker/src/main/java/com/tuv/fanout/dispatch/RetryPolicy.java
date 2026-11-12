package com.tuv.fanout.dispatch;

import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Bounded retries with exponential backoff and jitter. */
public final class RetryPolicy {

    private final int maxDeliveries;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final RandomGenerator random;

    public RetryPolicy(int maxDeliveries, Duration baseDelay, Duration maxDelay, RandomGenerator random) {
        if (maxDeliveries < 1) {
            throw new IllegalArgumentException("at least one delivery is required");
        }
        if (baseDelay.isNegative() || maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("require 0 <= baseDelay <= maxDelay");
        }
        this.maxDeliveries = maxDeliveries;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.random = Objects.requireNonNull(random, "random");
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(3, Duration.ofSeconds(30), Duration.ofMinutes(10), RandomGenerator.getDefault());
    }

    public int maxDeliveries() {
        return maxDeliveries;
    }

    public boolean isExhausted(int deliveryCount) {
        return deliveryCount >= maxDeliveries;
    }

    /**
     * Delay before the delivery that follows {@code deliveryCount}. The exponential value
     * is capped, then drawn from its upper half so retries spread out without ever firing
     * immediately.
     */
    public Duration backoff(int deliveryCount) {
        int doublings = Math.min(Math.max(deliveryCount - 1, 0), 30);
        long maxMillis = maxDelay.toMillis();
        long baseMillis = baseDelay.toMillis();
        long cappedMillis = baseMillis > (maxMillis >> doublings) ? maxMillis : baseMillis << doublings;
        long half = cappedMillis / 2;
        return Duration.ofMillis(half + random.nextLong(cappedMillis - half + 1));
    }
}
