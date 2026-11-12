package com.tuv.fanout.dispatch;

import java.time.Duration;
import java.util.Objects;

/**
 * <p>Rules: no component is null; the poll interval, attempt timeout and idle delay are
 * strictly positive; the shutdown grace is not negative; the lease lasts at least twice the
 * poll interval, so one late renewal does not lose the item.
 *
 * @param leaseDuration      how long an item stays owned without a renewal; also the time
 *                           after which a crashed worker's item is picked up again
 * @param statusPollInterval pause between two downstream status checks
 * @param attemptTimeout     how long to wait for one attempt before treating its outcome
 *                           as unknown
 * @param idleDelay          longest the dispatcher sleeps when there is nothing to do
 * @param shutdownGrace      how long {@code close()} waits for in-flight items
 */
public record DispatcherConfig(
        Duration leaseDuration,
        Duration statusPollInterval,
        Duration attemptTimeout,
        Duration idleDelay,
        Duration shutdownGrace) {

    public DispatcherConfig {
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        Objects.requireNonNull(statusPollInterval, "statusPollInterval");
        Objects.requireNonNull(attemptTimeout, "attemptTimeout");
        Objects.requireNonNull(idleDelay, "idleDelay");
        Objects.requireNonNull(shutdownGrace, "shutdownGrace");
        requirePositive(statusPollInterval, "statusPollInterval");
        requirePositive(attemptTimeout, "attemptTimeout");
        requirePositive(idleDelay, "idleDelay");
        if (shutdownGrace.isNegative()) {
            throw new IllegalArgumentException("shutdownGrace must not be negative");
        }
        if (leaseDuration.compareTo(statusPollInterval.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException(
                    "lease must last at least twice the poll interval, or it expires between renewals");
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
                Duration.ofSeconds(30));
    }
}
