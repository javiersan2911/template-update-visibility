package com.tuv.fanout.capacity;

import java.time.Duration;
import java.util.Objects;

/**
 * @param floor            the global limit never drops below this
 * @param ceiling          the global limit never rises above this
 * @param initialLimit     global limit at start-up
 * @param perFirmCap       concurrent items allowed for a single firm
 * @param increaseStep     slots added per healthy interval
 * @param increaseInterval minimum time between two increases
 * @param decreaseFactor   multiplier applied on overload, in (0, 1)
 * @param decreaseCooldown minimum time between two decreases, so a burst of rejections
 *                         from one overload episode counts once
 */
public record AimdConfig(
        int floor,
        int ceiling,
        int initialLimit,
        int perFirmCap,
        int increaseStep,
        Duration increaseInterval,
        double decreaseFactor,
        Duration decreaseCooldown) {

    public AimdConfig {
        Objects.requireNonNull(increaseInterval, "increaseInterval");
        Objects.requireNonNull(decreaseCooldown, "decreaseCooldown");
        if (floor < 1 || ceiling < floor || initialLimit < floor || initialLimit > ceiling) {
            throw new IllegalArgumentException("require 1 <= floor <= initialLimit <= ceiling");
        }
        if (perFirmCap < 1 || increaseStep < 1) {
            throw new IllegalArgumentException("per-firm cap and increase step must be positive");
        }
        if (decreaseFactor <= 0 || decreaseFactor >= 1) {
            throw new IllegalArgumentException("decrease factor must be in (0, 1)");
        }
    }

    /** Values from the architecture: 10..100 global, 50 per firm, +5 per 5 minutes, halve on overload. */
    public static AimdConfig defaults() {
        return new AimdConfig(
                10, 100, 10, 50, 5, Duration.ofMinutes(5), 0.5, Duration.ofMinutes(1));
    }

    /** A constant global limit, for callers that do not want adaptation. */
    public static AimdConfig fixed(int globalLimit, int perFirmCap) {
        return new AimdConfig(
                globalLimit, globalLimit, globalLimit, perFirmCap, 1, Duration.ZERO, 0.5, Duration.ZERO);
    }
}
