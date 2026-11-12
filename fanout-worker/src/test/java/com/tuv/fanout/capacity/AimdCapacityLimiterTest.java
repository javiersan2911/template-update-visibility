package com.tuv.fanout.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import com.tuv.fanout.domain.FirmId;
import com.tuv.fanout.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AimdCapacityLimiterTest {

    private static final FirmId FIRM_A = new FirmId("firm-a");
    private static final FirmId FIRM_B = new FirmId("firm-b");
    private static final Duration INCREASE_INTERVAL = Duration.ofMinutes(5);
    private static final Duration DECREASE_COOLDOWN = Duration.ofMinutes(1);

    private final MutableClock clock = new MutableClock();

    private AimdCapacityLimiter limiter(int floor, int ceiling, int initial, int perFirmCap) {
        return new AimdCapacityLimiter(
                new AimdConfig(floor, ceiling, initial, perFirmCap, 5, INCREASE_INTERVAL, 0.5, DECREASE_COOLDOWN),
                clock);
    }

    @Test
    void refusesAdmissionBeyondTheGlobalLimit() {
        AimdCapacityLimiter limiter = limiter(2, 2, 2, 10);

        assertThat(limiter.tryAcquire(FIRM_A)).isPresent();
        assertThat(limiter.tryAcquire(FIRM_B)).isPresent();

        assertThat(limiter.hasCapacity()).isFalse();
        assertThat(limiter.tryAcquire(FIRM_A)).isEmpty();
    }

    @Test
    void capsOneFirmWithoutBlockingOthers() {
        AimdCapacityLimiter limiter = limiter(10, 10, 10, 1);

        assertThat(limiter.tryAcquire(FIRM_A)).isPresent();

        assertThat(limiter.tryAcquire(FIRM_A)).isEmpty();
        assertThat(limiter.tryAcquire(FIRM_B)).isPresent();
    }

    @Test
    void releasingAPermitFreesExactlyOneSlotEvenWhenClosedTwice() {
        AimdCapacityLimiter limiter = limiter(2, 2, 2, 2);
        Permit first = limiter.tryAcquire(FIRM_A).orElseThrow();
        limiter.tryAcquire(FIRM_A).orElseThrow();

        first.close();
        first.close();

        assertThat(limiter.tryAcquire(FIRM_A)).isPresent();
        assertThat(limiter.tryAcquire(FIRM_A)).isEmpty();
    }

    @Test
    void halvesTheLimitOnOverloadButNeverBelowTheFloor() {
        AimdCapacityLimiter limiter = limiter(10, 100, 40, 50);

        limiter.recordOverload(Duration.ZERO);
        assertThat(limiter.currentLimit()).isEqualTo(20);

        clock.advance(DECREASE_COOLDOWN);
        limiter.recordOverload(Duration.ZERO);
        clock.advance(DECREASE_COOLDOWN);
        limiter.recordOverload(Duration.ZERO);
        assertThat(limiter.currentLimit()).isEqualTo(10);
    }

    @Test
    void countsABurstOfOverloadSignalsOnce() {
        AimdCapacityLimiter limiter = limiter(10, 100, 80, 50);

        limiter.recordOverload(Duration.ZERO);
        limiter.recordOverload(Duration.ZERO);
        limiter.recordOverload(Duration.ZERO);

        assertThat(limiter.currentLimit()).isEqualTo(40);
    }

    @Test
    void growsByOneStepPerHealthyIntervalUpToTheCeiling() {
        AimdCapacityLimiter limiter = limiter(10, 18, 10, 50);

        limiter.recordSuccess();
        assertThat(limiter.currentLimit()).as("too early to grow").isEqualTo(10);

        clock.advance(INCREASE_INTERVAL);
        limiter.recordSuccess();
        limiter.recordSuccess();
        assertThat(limiter.currentLimit()).isEqualTo(15);

        clock.advance(INCREASE_INTERVAL);
        limiter.recordSuccess();
        assertThat(limiter.currentLimit()).isEqualTo(18);
    }

    @Test
    void overloadRestartsTheWaitBeforeTheNextIncrease() {
        AimdCapacityLimiter limiter = limiter(10, 100, 40, 50);
        clock.advance(INCREASE_INTERVAL.minusSeconds(1));
        limiter.recordOverload(Duration.ZERO);

        clock.advance(Duration.ofSeconds(1));
        limiter.recordSuccess();

        assertThat(limiter.currentLimit()).isEqualTo(20);
    }

    @Test
    void admitsNothingWhileTheDownstreamAskedCallersToStayAway() {
        AimdCapacityLimiter limiter = limiter(10, 100, 40, 50);

        limiter.recordOverload(Duration.ofSeconds(30));

        assertThat(limiter.hasCapacity()).isFalse();
        assertThat(limiter.tryAcquire(FIRM_A)).isEmpty();
        clock.advance(Duration.ofSeconds(30));
        assertThat(limiter.tryAcquire(FIRM_A)).isPresent();
    }

    @Test
    void aShorterPauseDoesNotCutALongerOneShort() {
        AimdCapacityLimiter limiter = limiter(10, 100, 40, 50);
        limiter.recordOverload(Duration.ofSeconds(30));

        limiter.recordOverload(Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(29));

        assertThat(limiter.hasCapacity()).isFalse();
    }

    @Test
    void loweringTheLimitKeepsExistingPermitsAndBlocksNewOnes() {
        AimdCapacityLimiter limiter = limiter(1, 4, 4, 4);
        Permit held = limiter.tryAcquire(FIRM_A).orElseThrow();
        limiter.tryAcquire(FIRM_A).orElseThrow();
        limiter.tryAcquire(FIRM_A).orElseThrow();

        limiter.recordOverload(Duration.ZERO);

        assertThat(limiter.currentLimit()).isEqualTo(2);
        assertThat(limiter.tryAcquire(FIRM_B)).isEmpty();
        held.close();
        assertThat(limiter.tryAcquire(FIRM_B)).as("still 2 in flight against a limit of 2").isEmpty();
    }
}
