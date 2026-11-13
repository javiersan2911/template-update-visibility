package com.tuv.fanout.dispatch;

import java.time.Duration;
import java.util.Objects;

/**
 * <p>Rules: no component is null; the poll interval, attempt timeout and idle delay are
 * strictly positive; the call timeout is positive and the lease covers one renewal cycle in the
 * worst case, {@code 2 * callTimeout + statusPollInterval <= leaseDuration} (the renew call, the
 * status call and the poll sleep between two renewals, each call running to its deadline), so
 * hung calls alone cannot let the lease expire; the shutdown grace is not negative; the lease
 * lasts at least twice the poll interval, so one late renewal does not lose the item. The last
 * rule is not implied by the one before it, since the call timeout can be tiny.
 *
 * @param leaseDuration      how long an item stays owned without a renewal; also the time
 *                           after which a crashed worker's item is picked up again
 * @param statusPollInterval pause between two downstream status checks
 * @param attemptTimeout     how long to wait for one attempt before treating its outcome
 *                           as unknown
 * @param idleDelay          longest the dispatcher sleeps when there is nothing to do
 * @param shutdownGrace      how long {@code close()} waits for in-flight items
 * @param callTimeout        longest the worker waits for any one call to the gateway, the
 *                           offer verifier or the item store before abandoning it and treating
 *                           it as a failed call; enforced by the worker, whatever the adapter does
 */
public record DispatcherConfig(
        Duration leaseDuration,
        Duration statusPollInterval,
        Duration attemptTimeout,
        Duration idleDelay,
        Duration shutdownGrace,
        Duration callTimeout) {

    public DispatcherConfig {
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        Objects.requireNonNull(statusPollInterval, "statusPollInterval");
        Objects.requireNonNull(attemptTimeout, "attemptTimeout");
        Objects.requireNonNull(idleDelay, "idleDelay");
        Objects.requireNonNull(shutdownGrace, "shutdownGrace");
        Objects.requireNonNull(callTimeout, "callTimeout");
        requirePositive(statusPollInterval, "statusPollInterval");
        requirePositive(attemptTimeout, "attemptTimeout");
        requirePositive(idleDelay, "idleDelay");
        requirePositive(callTimeout, "callTimeout");
        if (shutdownGrace.isNegative()) {
            throw new IllegalArgumentException("shutdownGrace must not be negative");
        }
        if (leaseDuration.compareTo(statusPollInterval.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException(
                    "lease must last at least twice the poll interval, or it expires between renewals");
        }
        if (!renewalCycleFits(leaseDuration, statusPollInterval, callTimeout)) {
            throw new IllegalArgumentException("lease must cover 2 * call timeout + poll interval, or hung calls"
                    + " alone can let it expire between renewals");
        }
    }

    /** An overflowing sum is longer than any lease, so it does not fit. */
    private static boolean renewalCycleFits(Duration lease, Duration poll, Duration callTimeout) {
        try {
            return lease.compareTo(callTimeout.multipliedBy(2).plus(poll)) >= 0;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    public static DispatcherConfig defaults() {
        return new DispatcherConfig(
                Duration.ofMinutes(5),
                Duration.ofSeconds(5),
                Duration.ofMinutes(10),
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofSeconds(30));
    }
}
