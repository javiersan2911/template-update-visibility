package com.tuv.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LeaseTest {

    private static final WorkItem ITEM = WorkItem.create(
            new FirmId("firm-a"), new EngagementId("e1"), new VersionPin("audit", 7)).delivered();
    private static final Instant EXPIRY = Instant.parse("2026-11-12T00:05:00Z");

    @ParameterizedTest(name = "token {0}")
    @ValueSource(longs = {-1, -42, Long.MIN_VALUE})
    void aNegativeFencingTokenIsRejected(long token) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Lease(ITEM, token, EXPIRY))
                .withMessageContaining("token");
    }

    @ParameterizedTest(name = "token {0}")
    @ValueSource(longs = {0, 1, Long.MAX_VALUE})
    void anyNonNegativeFencingTokenIsAccepted(long token) {
        assertThat(new Lease(ITEM, token, EXPIRY).token()).isEqualTo(token);
    }

    @Test
    void anItemAndAnExpiryAreRequired() {
        assertThatNullPointerException().isThrownBy(() -> new Lease(null, 1, EXPIRY)).withMessageContaining("item");
        assertThatNullPointerException().isThrownBy(() -> new Lease(ITEM, 1, null))
                .withMessageContaining("expiresAt");
    }
}
