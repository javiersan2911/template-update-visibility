package com.tuv.fanout.dispatch;

import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Bounded retries with exponential backoff and jitter. */
public final class RetryPolicy {

    static final int DEFAULT_MAX_DEFERRALS = 20;

    private final int maxDeliveries;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final int maxDeferrals;
    private final RandomGenerator random;

    /** Allows {@value #DEFAULT_MAX_DEFERRALS} deferrals in a row. */
    public RetryPolicy(int maxDeliveries, Duration baseDelay, Duration maxDelay, RandomGenerator random) {
        this(maxDeliveries, baseDelay, maxDelay, DEFAULT_MAX_DEFERRALS, random);
    }

    /**
     * @param maxDeferrals how many times in a row an item may be sent back without an attempt
     *                     (throttled, dependency down) before it is failed; at least 1
     */
    public RetryPolicy(
            int maxDeliveries, Duration baseDelay, Duration maxDelay, int maxDeferrals, RandomGenerator random) {
        if (maxDeliveries < 1) {
            throw new IllegalArgumentException("at least one delivery is required");
        }
        Objects.requireNonNull(baseDelay, "baseDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (baseDelay.isNegative() || maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("require 0 <= baseDelay <= maxDelay");
        }
        if (maxDeferrals < 1) {
            throw new IllegalArgumentException("at least one deferral must be allowed");
        }
        this.maxDeliveries = maxDeliveries;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.maxDeferrals = maxDeferrals;
        this.random = Objects.requireNonNull(random, "random");
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(3, Duration.ofSeconds(30), Duration.ofMinutes(10), RandomGenerator.getDefault());
    }

    public int maxDeliveries() {
        return maxDeliveries;
    }

    public Duration maxDelay() {
        return maxDelay;
    }

    public int maxDeferrals() {
        return maxDeferrals;
    }

    /** Whether an item already deferred {@code deferrals} times in a row may not be deferred again. */
    public boolean isDeferralLimitReached(int deferrals) {
        return deferrals >= maxDeferrals;
    }

    public boolean isExhausted(int deliveryCount) {
        return deliveryCount >= maxDeliveries;
    }

    /**
     * Delay before the delivery that follows {@code deliveryCount}. The exponential value
     * is capped, then drawn from its upper half so retries spread out without ever firing
     * immediately. A delay too large for milliseconds in a {@code long} counts as the
     * largest one that fits, so no configuration makes this method throw.
     */
    public Duration backoff(int deliveryCount) {
        int doublings = Math.min(Math.max(deliveryCount - 1, 0), 30);
        long maxMillis = millisSaturating(maxDelay);
        long baseMillis = millisSaturating(baseDelay);
        long cappedMillis = baseMillis > (maxMillis >> doublings) ? maxMillis : baseMillis << doublings;
        long half = cappedMillis / 2;
        return Duration.ofMillis(half + random.nextLong(cappedMillis - half + 1));
    }

    private static long millisSaturating(Duration duration) {
        try {
            return duration.toMillis();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }
}
