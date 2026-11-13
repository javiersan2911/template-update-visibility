package com.tuv.fanout.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DispatcherConfigTest {

    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration POLL = Duration.ofSeconds(5);
    private static final Duration TIMEOUT = Duration.ofMinutes(10);
    private static final Duration IDLE = Duration.ofSeconds(1);
    private static final Duration GRACE = Duration.ofSeconds(30);

    private static DispatcherConfig config(
            Duration lease, Duration poll, Duration timeout, Duration idle, Duration grace) {
        return new DispatcherConfig(lease, poll, timeout, idle, grace);
    }

    static Stream<Arguments> nullComponents() {
        return Stream.of(
                Arguments.of("leaseDuration", (Runnable) () -> config(null, POLL, TIMEOUT, IDLE, GRACE)),
                Arguments.of("statusPollInterval", (Runnable) () -> config(LEASE, null, TIMEOUT, IDLE, GRACE)),
                Arguments.of("attemptTimeout", (Runnable) () -> config(LEASE, POLL, null, IDLE, GRACE)),
                Arguments.of("idleDelay", (Runnable) () -> config(LEASE, POLL, TIMEOUT, null, GRACE)),
                Arguments.of("shutdownGrace", (Runnable) () -> config(LEASE, POLL, TIMEOUT, IDLE, null)));
    }

    static Stream<Arguments> invalidDurations() {
        Duration negative = Duration.ofMillis(-1);
        return Stream.of(
                Arguments.of("zero poll interval", (Runnable) () -> config(LEASE, Duration.ZERO, TIMEOUT, IDLE, GRACE)),
                Arguments.of("negative poll interval", (Runnable) () -> config(LEASE, negative, TIMEOUT, IDLE, GRACE)),
                Arguments.of("zero attempt timeout", (Runnable) () -> config(LEASE, POLL, Duration.ZERO, IDLE, GRACE)),
                Arguments.of("negative attempt timeout", (Runnable) () -> config(LEASE, POLL, negative, IDLE, GRACE)),
                Arguments.of("zero idle delay", (Runnable) () -> config(LEASE, POLL, TIMEOUT, Duration.ZERO, GRACE)),
                Arguments.of("negative idle delay", (Runnable) () -> config(LEASE, POLL, TIMEOUT, negative, GRACE)),
                Arguments.of("negative shutdown grace", (Runnable) () -> config(LEASE, POLL, TIMEOUT, IDLE, negative)),
                Arguments.of("lease equal to the poll interval", (Runnable) () -> config(POLL, POLL, TIMEOUT, IDLE, GRACE)),
                Arguments.of("lease just under twice the poll interval",
                        (Runnable) () -> config(POLL.multipliedBy(2).minusNanos(1), POLL, TIMEOUT, IDLE, GRACE)),
                Arguments.of("zero lease", (Runnable) () -> config(Duration.ZERO, POLL, TIMEOUT, IDLE, GRACE)));
    }

    @ParameterizedTest(name = "{0} is null")
    @MethodSource("nullComponents")
    void rejectsANullComponent(String component, Runnable construct) {
        assertThatNullPointerException().isThrownBy(construct::run).withMessageContaining(component);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDurations")
    void rejectsDurationsThatBreakTheTimingRules(String why, Runnable construct) {
        assertThatIllegalArgumentException().isThrownBy(construct::run);
    }

    @Test
    void acceptsALeaseOfExactlyTwiceThePollIntervalAndAZeroShutdownGrace() {
        DispatcherConfig config = config(POLL.multipliedBy(2), POLL, TIMEOUT, IDLE, Duration.ZERO);

        assertThat(config.shutdownGrace()).isZero();
        assertThat(config.leaseDuration()).isEqualTo(POLL.multipliedBy(2));
    }

    @Test
    void theDefaultsAreValid() {
        assertThat(DispatcherConfig.defaults().leaseDuration()).isEqualTo(Duration.ofMinutes(5));
    }
}
