package com.tuv.fanout.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class RetryPolicyTest {

    private final RetryPolicy policy =
            new RetryPolicy(3, Duration.ofSeconds(10), Duration.ofSeconds(60), new Random(42));

    @Test
    void backoffDoublesPerDeliveryAndStaysInTheUpperHalf() {
        for (int i = 0; i < 100; i++) {
            assertThat(policy.backoff(1)).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(10));
            assertThat(policy.backoff(2)).isBetween(Duration.ofSeconds(10), Duration.ofSeconds(20));
            assertThat(policy.backoff(3)).isBetween(Duration.ofSeconds(20), Duration.ofSeconds(40));
        }
    }

    @Test
    void backoffIsCappedAtTheMaximumDelay() {
        assertThat(policy.backoff(4)).isBetween(Duration.ofSeconds(30), Duration.ofSeconds(60));
        assertThat(policy.backoff(1_000)).isBetween(Duration.ofSeconds(30), Duration.ofSeconds(60));
    }

    @Test
    void aHugeBaseDelayDoesNotOverflowPastTheCap() {
        RetryPolicy huge = new RetryPolicy(3, Duration.ofDays(400), Duration.ofDays(800), new Random(42));

        assertThat(huge.backoff(40)).isBetween(Duration.ofDays(400), Duration.ofDays(800));
    }

    @Test
    void isExhaustedOnceTheDeliveryBudgetIsUsed() {
        assertThat(policy.isExhausted(2)).isFalse();
        assertThat(policy.isExhausted(3)).isTrue();
    }

    @Test
    void anItemMayBeDeferredTwentyTimesInARowUnlessConfigured() {
        assertThat(policy.maxDeferrals()).isEqualTo(20);
        assertThat(policy.isDeferralLimitReached(19)).isFalse();
        assertThat(policy.isDeferralLimitReached(20)).isTrue();

        RetryPolicy strict = new RetryPolicy(3, Duration.ofSeconds(10), Duration.ofSeconds(60), 2, new Random(42));
        assertThat(strict.isDeferralLimitReached(1)).isFalse();
        assertThat(strict.isDeferralLimitReached(2)).isTrue();
    }

    @Test
    void atLeastOneDeferralMustBeAllowed() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new RetryPolicy(3, Duration.ofSeconds(10), Duration.ofSeconds(60), 0, new Random(42)));
        assertThat(new RetryPolicy(3, Duration.ofSeconds(10), Duration.ofSeconds(60), 1, new Random(42))
                .maxDeferrals()).isEqualTo(1);
    }

    @Test
    void exposesTheMaximumDelay() {
        assertThat(policy.maxDelay()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void theDefaultsAllowThreeDeliveries() {
        assertThat(RetryPolicy.defaults().maxDeliveries()).isEqualTo(3);
        assertThat(RetryPolicy.defaults().isExhausted(2)).isFalse();
        assertThat(RetryPolicy.defaults().isExhausted(3)).isTrue();
    }

    @Test
    void bothDelaysMustBeGivenAndTheMessageNamesTheMissingOne() {
        assertThatNullPointerException()
                .isThrownBy(() -> new RetryPolicy(3, null, Duration.ofSeconds(60), new Random(42)))
                .withMessageContaining("baseDelay");
        assertThatNullPointerException()
                .isThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(10), null, new Random(42)))
                .withMessageContaining("maxDelay");
    }

    static Stream<Arguments> hugeDelays() {
        Duration huge = Duration.ofSeconds(Long.MAX_VALUE);
        return Stream.of(
                Arguments.of("huge max, small base", Duration.ofSeconds(1), huge),
                Arguments.of("huge max and base", huge, huge),
                Arguments.of("max just past what toMillis can hold", Duration.ofSeconds(1),
                        Duration.ofMillis(Long.MAX_VALUE).plusMillis(1)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hugeDelays")
    void aDelayTooLargeForMillisecondsSaturatesInsteadOfThrowing(String why, Duration base, Duration max) {
        RetryPolicy policy = new RetryPolicy(3, base, max, new Random(42));

        for (int deliveryCount : new int[] {1, 2, 31, 1_000, Integer.MAX_VALUE}) {
            assertThat(policy.backoff(deliveryCount)).as("delivery %d", deliveryCount)
                    .isPositive().isLessThanOrEqualTo(Duration.ofMillis(Long.MAX_VALUE));
        }
    }

    @Test
    void aSaturatedCapStillLetsASmallBaseGrowFromItsOwnValue() {
        RetryPolicy policy = new RetryPolicy(
                3, Duration.ofSeconds(1), Duration.ofSeconds(Long.MAX_VALUE), new Random(42));

        assertThat(policy.backoff(1)).isBetween(Duration.ofMillis(500), Duration.ofSeconds(1));
        assertThat(policy.backoff(3)).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @ParameterizedTest(name = "{0} deliveries")
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void atLeastOneDeliveryIsRequired(int deliveries) {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new RetryPolicy(deliveries, Duration.ofSeconds(10), Duration.ofSeconds(60), new Random(42)));
    }
}
