package com.tuv.fanout.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AimdConfigTest {

    private static AimdConfig withMaxPause(Duration maxPause) {
        return new AimdConfig(10, 100, 10, 50, 5, Duration.ofMinutes(5), 0.5, Duration.ofMinutes(1), maxPause);
    }

    @Test
    void theLongestPauseDefaultsToFifteenMinutes() {
        assertThat(AimdConfig.defaults().maxPause()).isEqualTo(Duration.ofMinutes(15));
        assertThat(AimdConfig.fixed(4, 4).maxPause()).isEqualTo(Duration.ofMinutes(15));
        assertThat(new AimdConfig(1, 2, 1, 1, 1, Duration.ZERO, 0.5, Duration.ZERO).maxPause())
                .isEqualTo(Duration.ofMinutes(15));
    }

    @ParameterizedTest(name = "{0} ms")
    @ValueSource(longs = {0, -1, -60_000})
    void theLongestPauseMustBePositive(long millis) {
        assertThatIllegalArgumentException().isThrownBy(() -> withMaxPause(Duration.ofMillis(millis)));
    }

    @Test
    void theLongestPauseMustBeGiven() {
        assertThatNullPointerException().isThrownBy(() -> withMaxPause(null)).withMessageContaining("maxPause");
    }

    @Test
    void firmCapsAdaptUnlessTheConfigIsFixed() {
        assertThat(AimdConfig.defaults().adaptiveFirmCaps()).isTrue();
        assertThat(withMaxPause(Duration.ofMinutes(1)).adaptiveFirmCaps()).isTrue();
        assertThat(AimdConfig.fixed(4, 4).adaptiveFirmCaps()).isFalse();
    }

    @Test
    void theLongestPauseIsLimitedToOneDay() {
        assertThat(withMaxPause(Duration.ofDays(1)).maxPause()).isEqualTo(Duration.ofDays(1));
        assertThatIllegalArgumentException().isThrownBy(() -> withMaxPause(Duration.ofDays(1).plusNanos(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> withMaxPause(Duration.ofSeconds(Long.MAX_VALUE)));
    }

    @Test
    void negativeIntervalsAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AimdConfig(
                10, 100, 10, 50, 5, Duration.ofSeconds(-1), 0.5, Duration.ofMinutes(1), Duration.ofMinutes(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> new AimdConfig(
                10, 100, 10, 50, 5, Duration.ofMinutes(5), 0.5, Duration.ofSeconds(-1), Duration.ofMinutes(1)));
    }

    @ParameterizedTest(name = "factor {0}")
    @ValueSource(doubles = {Double.NaN, 0.0, 1.0, -0.5, 1.5, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void theDecreaseFactorMustBeAStrictFractionAndNotNaN(double factor) {
        assertThatIllegalArgumentException().isThrownBy(() -> new AimdConfig(
                10, 100, 10, 50, 5, Duration.ofMinutes(5), factor, Duration.ofMinutes(1), Duration.ofMinutes(1)));
    }
}
