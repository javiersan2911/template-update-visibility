package com.tuv.fanout.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

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
}
