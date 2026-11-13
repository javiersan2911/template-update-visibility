package com.tuv.fanout.capacity;

import java.time.Duration;
import java.util.Objects;

/**
 * @param floor            the global limit never drops below this
 * @param ceiling          the global limit never rises above this
 * @param initialLimit     global limit at start-up
 * @param perFirmCap       concurrent items allowed for a single firm; at most {@code ceiling}
 * @param increaseStep     slots added per healthy interval
 * @param increaseInterval minimum time between two increases
 * @param decreaseFactor   multiplier applied on overload, in (0, 1)
 * @param decreaseCooldown minimum time between two decreases, so a burst of rejections
 *                         from one overload episode counts once
 * @param maxPause         longest the limiter pauses admissions for one overload signal,
 *                         whatever wait the downstream system asked for; positive, at most
 *                         {@link #LONGEST_ALLOWED_PAUSE}
 * @param adaptiveFirmCaps whether firm-scoped trouble lowers that firm's cap. Off in
 *                         {@link #fixed}, where {@code recordFirmTrouble} does nothing
 */
public record AimdConfig(
        int floor,
        int ceiling,
        int initialLimit,
        int perFirmCap,
        int increaseStep,
        Duration increaseInterval,
        double decreaseFactor,
        Duration decreaseCooldown,
        Duration maxPause,
        boolean adaptiveFirmCaps) {

    /** The largest {@code maxPause} accepted; anything longer is a configuration mistake. */
    public static final Duration LONGEST_ALLOWED_PAUSE = Duration.ofDays(1);

    /** Longest pause for one overload signal unless configured otherwise. */
    public static final Duration DEFAULT_MAX_PAUSE = Duration.ofMinutes(15);

    /** Same as the full constructor with the default {@link #DEFAULT_MAX_PAUSE}. */
    public AimdConfig(
            int floor,
            int ceiling,
            int initialLimit,
            int perFirmCap,
            int increaseStep,
            Duration increaseInterval,
            double decreaseFactor,
            Duration decreaseCooldown) {
        this(floor, ceiling, initialLimit, perFirmCap, increaseStep, increaseInterval,
                decreaseFactor, decreaseCooldown, DEFAULT_MAX_PAUSE);
    }

    /** Same as the full constructor with per-firm caps that adapt. */
    public AimdConfig(
            int floor,
            int ceiling,
            int initialLimit,
            int perFirmCap,
            int increaseStep,
            Duration increaseInterval,
            double decreaseFactor,
            Duration decreaseCooldown,
            Duration maxPause) {
        this(floor, ceiling, initialLimit, perFirmCap, increaseStep, increaseInterval,
                decreaseFactor, decreaseCooldown, maxPause, true);
    }

    public AimdConfig {
        Objects.requireNonNull(increaseInterval, "increaseInterval");
        Objects.requireNonNull(decreaseCooldown, "decreaseCooldown");
        Objects.requireNonNull(maxPause, "maxPause");
        if (maxPause.isNegative() || maxPause.isZero() || maxPause.compareTo(LONGEST_ALLOWED_PAUSE) > 0) {
            throw new IllegalArgumentException("max pause must be positive and at most " + LONGEST_ALLOWED_PAUSE);
        }
        if (increaseInterval.isNegative() || decreaseCooldown.isNegative()) {
            throw new IllegalArgumentException("increase interval and decrease cooldown must not be negative");
        }
        if (floor < 1 || ceiling < floor || initialLimit < floor || initialLimit > ceiling) {
            throw new IllegalArgumentException("require 1 <= floor <= initialLimit <= ceiling");
        }
        if (perFirmCap < 1 || increaseStep < 1) {
            throw new IllegalArgumentException("per-firm cap and increase step must be positive");
        }
        if (perFirmCap > ceiling) {
            throw new IllegalArgumentException("per-firm cap must not exceed the ceiling of the global limit");
        }
        if (!(decreaseFactor > 0 && decreaseFactor < 1)) {
            throw new IllegalArgumentException("decrease factor must be in (0, 1)");
        }
    }

    /** Values from the architecture: 10..100 global, 50 per firm, +5 per 5 minutes, halve on overload. */
    public static AimdConfig defaults() {
        return new AimdConfig(
                10, 100, 10, 50, 5, Duration.ofMinutes(5), 0.5, Duration.ofMinutes(1));
    }

    /**
     * A constant global limit and constant per-firm caps, for callers that do not want
     * adaptation: firm trouble changes nothing. A 429 still pauses admissions.
     */
    public static AimdConfig fixed(int globalLimit, int perFirmCap) {
        return new AimdConfig(globalLimit, globalLimit, globalLimit, perFirmCap, 1, Duration.ZERO, 0.5,
                Duration.ZERO, DEFAULT_MAX_PAUSE, false);
    }
}
