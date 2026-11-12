package com.tuv.fanout.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

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
}
