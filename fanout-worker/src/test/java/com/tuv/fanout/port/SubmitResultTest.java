package com.tuv.fanout.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.tuv.fanout.port.SubmitResult.Rejected;
import com.tuv.fanout.port.SubmitResult.Throttled;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SubmitResultTest {

    @Test
    void aRejectionNeedsAKindAndAReason() {
        assertThatNullPointerException().isThrownBy(() -> new Rejected(null, "why"));
        assertThatNullPointerException().isThrownBy(() -> new Rejected(Rejected.Kind.OTHER, null));
    }

    @Test
    void aMissingOrNegativeRetryHintMeansNoSpecificWait() {
        assertThat(new Throttled(null).retryAfter()).isEqualTo(Duration.ZERO);
        assertThat(new Throttled(Duration.ofSeconds(-5)).retryAfter()).isEqualTo(Duration.ZERO);
        assertThat(new Throttled(Duration.ofSeconds(5)).retryAfter()).isEqualTo(Duration.ofSeconds(5));
    }
}
