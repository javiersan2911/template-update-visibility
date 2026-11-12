package com.tuv.fanout.dispatch;

import java.time.Duration;

/**
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
        if (leaseDuration.compareTo(statusPollInterval) <= 0) {
            throw new IllegalArgumentException(
                    "lease must outlive the poll interval, or it expires between renewals");
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
