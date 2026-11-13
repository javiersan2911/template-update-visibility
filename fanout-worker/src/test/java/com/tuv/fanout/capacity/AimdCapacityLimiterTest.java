package com.tuv.fanout.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.tuv.fanout.domain.FirmId;
import com.tuv.fanout.support.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        AimdCapacityLimiter limiter = limiter(2, 2, 2, 2);

        assertThat(limiter.tryAcquire(FIRM_A, 1)).isPresent();
        assertThat(limiter.tryAcquire(FIRM_B, 1)).isPresent();

        assertThat(limiter.hasCapacity()).isFalse();
        assertThat(limiter.tryAcquire(FIRM_A, 1)).isEmpty();
    }

    @Test
    void capsOneFirmWithoutBlockingOthers() {
        AimdCapacityLimiter limiter = limiter(10, 10, 10, 1);

        assertThat(limiter.tryAcquire(FIRM_A, 1)).isPresent();

        assertThat(limiter.tryAcquire(FIRM_A, 1)).isEmpty();
        assertThat(limiter.tryAcquire(FIRM_B, 1)).isPresent();
    }

    @Test
    void releasingAPermitFreesExactlyOneSlotEvenWhenClosedTwice() {
        AimdCapacityLimiter limiter = limiter(2, 2, 2, 2);
        Permit first = limiter.tryAcquire(FIRM_A, 1).orElseThrow();
        limiter.tryAcquire(FIRM_A, 1).orElseThrow();

        first.close();
        first.close();

        assertThat(limiter.tryAcquire(FIRM_A, 1)).isPresent();
        assertThat(limiter.tryAcquire(FIRM_A, 1)).isEmpty();
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
        AimdCapacityLimiter limiter = limiter(10, 18, 10, 18);

        limiter.recordSuccess(FIRM_A);
        assertThat(limiter.currentLimit()).as("too early to grow").isEqualTo(10);

        clock.advance(INCREASE_INTERVAL);
        limiter.recordSuccess(FIRM_A);
        limiter.recordSuccess(FIRM_A);
        assertThat(limiter.currentLimit()).isEqualTo(15);

        clock.advance(INCREASE_INTERVAL);
        limiter.recordSuccess(FIRM_A);
        assertThat(limiter.currentLimit()).isEqualTo(18);
    }

    @Test
    void overloadRestartsTheWaitBeforeTheNextIncrease() {
        AimdCapacityLimiter limiter = limiter(10, 100, 40, 50);
        clock.advance(INCREASE_INTERVAL.minusSeconds(1));
        limiter.recordOverload(Duration.ZERO);

        clock.advance(Duration.ofSeconds(1));
        limiter.recordSuccess(FIRM_A);

        assertThat(limiter.currentLimit()).isEqualTo(20);
    }

    @Test
    void admitsNothingWhileTheDownstreamAskedCallersToStayAway() {
        AimdCapacityLimiter limiter = limiter(10, 100, 40, 50);

        limiter.recordOverload(Duration.ofSeconds(30));

        assertThat(limiter.hasCapacity()).isFalse();
        assertThat(limiter.tryAcquire(FIRM_A, 1)).isEmpty();
        clock.advance(Duration.ofSeconds(30));
        assertThat(limiter.tryAcquire(FIRM_A, 1)).isPresent();
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
        Permit held = limiter.tryAcquire(FIRM_A, 1).orElseThrow();
        limiter.tryAcquire(FIRM_A, 1).orElseThrow();
        limiter.tryAcquire(FIRM_A, 1).orElseThrow();

        limiter.recordOverload(Duration.ZERO);

        assertThat(limiter.currentLimit()).isEqualTo(2);
        assertThat(limiter.tryAcquire(FIRM_B, 1)).isEmpty();
        held.close();
        assertThat(limiter.tryAcquire(FIRM_B, 1)).as("still 2 in flight against a limit of 2").isEmpty();
    }

    private AimdCapacityLimiter limiterWithMaxPause(Duration maxPause) {
        return new AimdCapacityLimiter(
                new AimdConfig(10, 100, 40, 50, 5, INCREASE_INTERVAL, 0.5, DECREASE_COOLDOWN, maxPause), clock);
    }

    @Test
    void neverPausesAdmissionsLongerThanTheConfiguredMaximum() {
        AimdCapacityLimiter limiter = limiterWithMaxPause(Duration.ofMinutes(2));

        limiter.recordOverload(Duration.ofDays(3650));

        clock.advance(Duration.ofMinutes(2).minusSeconds(1));
        assertThat(limiter.hasCapacity()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.hasCapacity()).isTrue();
        assertThat(limiter.tryAcquire(FIRM_A, 1)).isPresent();
    }

    @Test
    void aPauseOfTheLargestPossibleDurationDoesNotOverflowTheClock() {
        AimdCapacityLimiter limiter = limiterWithMaxPause(AimdConfig.DEFAULT_MAX_PAUSE);

        limiter.recordOverload(Duration.ofSeconds(Long.MAX_VALUE));

        assertThat(limiter.hasCapacity()).isFalse();
        clock.advance(AimdConfig.DEFAULT_MAX_PAUSE);
        assertThat(limiter.hasCapacity()).isTrue();
    }

    @Test
    void aPauseShorterThanTheMaximumIsKeptAsAsked() {
        AimdCapacityLimiter limiter = limiterWithMaxPause(Duration.ofMinutes(2));

        limiter.recordOverload(Duration.ofSeconds(30));

        clock.advance(Duration.ofSeconds(29));
        assertThat(limiter.hasCapacity()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.hasCapacity()).isTrue();
    }

    @Test
    void aNegativePauseIsNoPause() {
        AimdCapacityLimiter limiter = limiterWithMaxPause(Duration.ofMinutes(2));

        limiter.recordOverload(Duration.ofSeconds(-30));

        assertThat(limiter.hasCapacity()).isTrue();
        assertThat(limiter.currentLimit()).as("still counts as an overload").isEqualTo(20);
    }

    // --- firm-scoped trouble

    private AimdCapacityLimiter firmLimiter(int globalLimit, int perFirmCap) {
        return limiter(10, globalLimit, globalLimit, perFirmCap);
    }

    @Test
    void oneFirmReportingTroubleRepeatedlyLowersOnlyItsOwnCap() {
        AimdCapacityLimiter limiter = firmLimiter(40, 20);

        limiter.recordFirmTrouble(FIRM_A);
        assertThat(limiter.currentFirmCap(FIRM_A)).isEqualTo(10);
        for (int expected : new int[] {5, 2, 1, 1}) {
            clock.advance(DECREASE_COOLDOWN);
            limiter.recordFirmTrouble(FIRM_A);
            assertThat(limiter.currentFirmCap(FIRM_A)).isEqualTo(expected);
        }

        assertThat(limiter.currentLimit()).as("the global limit is untouched").isEqualTo(40);
        assertThat(limiter.currentFirmCap(FIRM_B)).as("other firms are untouched").isEqualTo(20);
        assertThat(limiter.hasCapacity()).as("no pause").isTrue();
    }

    @Test
    void aBurstOfTroubleFromOneFirmHalvesItsCapOnce() {
        AimdCapacityLimiter limiter = firmLimiter(40, 20);

        limiter.recordFirmTrouble(FIRM_A);
        limiter.recordFirmTrouble(FIRM_A);
        limiter.recordFirmTrouble(FIRM_A);

        assertThat(limiter.currentFirmCap(FIRM_A)).isEqualTo(10);
    }

    @Test
    void aFirmCapNeverDropsBelowOne() {
        AimdCapacityLimiter limiter = firmLimiter(40, 3);

        for (int report = 0; report < 4; report++) {
            limiter.recordFirmTrouble(FIRM_A);
            clock.advance(DECREASE_COOLDOWN);
        }

        assertThat(limiter.currentFirmCap(FIRM_A)).isEqualTo(1);
        assertThat(limiter.tryAcquire(FIRM_A, 1)).isPresent();
        assertThat(limiter.tryAcquire(FIRM_A, 1)).isEmpty();
    }

    @Test
    void aFirmWithAFullCapOfOneHasNothingToLoseAndIsNotTracked() {
        AimdCapacityLimiter limiter = firmLimiter(40, 1);

        limiter.recordFirmTrouble(FIRM_A);

        assertThat(limiter.currentFirmCap(FIRM_A)).isEqualTo(1);
        assertThat(limiter.penalisedFirmCount()).isZero();
    }

    @Test
    void admissionsForAPenalisedFirmUseItsLoweredCapWhileOthersKeepTheirs() {
        AimdCapacityLimiter limiter = firmLimiter(40, 4);
        Permit held = limiter.tryAcquire(FIRM_A, 1).orElseThrow();
        limiter.tryAcquire(FIRM_A, 1).orElseThrow();
        limiter.tryAcquire(FIRM_A, 1).orElseThrow();

        limiter.recordFirmTrouble(FIRM_A);

        assertThat(limiter.currentFirmCap(FIRM_A)).isEqualTo(2);
        assertThat(limiter.tryAcquire(FIRM_A, 1)).as("3 in flight against a cap of 2").isEmpty();
        held.close();
        assertThat(limiter.tryAcquire(FIRM_A, 1)).as("2 in flight against a cap of 2").isEmpty();
        assertThat(limiter.tryAcquire(FIRM_B, 1)).isPresent();
    }

    @Test
    void troubleFromTwoDifferentFirmsWithinOneWindowLowersTheGlobalLimitOnce() {
        AimdCapacityLimiter limiter = firmLimiter(40, 20);

        limiter.recordFirmTrouble(FIRM_A);
        assertThat(limiter.currentLimit()).as("one firm is not the downstream system").isEqualTo(40);
        clock.advance(DECREASE_COOLDOWN.dividedBy(2));
        limiter.recordFirmTrouble(FIRM_B);

        assertThat(limiter.currentLimit()).isEqualTo(20);
        assertThat(limiter.currentFirmCap(FIRM_A)).isEqualTo(10);
        assertThat(limiter.currentFirmCap(FIRM_B)).isEqualTo(10);

        clock.advance(Duration.ofSeconds(1));
        limiter.recordFirmTrouble(new FirmId("firm-c"));
        limiter.recordFirmTrouble(FIRM_A);
        assertThat(limiter.currentLimit()).as("the same episode counts once").isEqualTo(20);
        assertThat(limiter.hasCapacity()).as("firm trouble never pauses admissions").isTrue();
    }

    @Test
    void troubleFromTwoFirmsInDifferentWindowsLeavesTheGlobalLimitAlone() {
        AimdCapacityLimiter limiter = firmLimiter(40, 20);

        limiter.recordFirmTrouble(FIRM_A);
        clock.advance(DECREASE_COOLDOWN);
        limiter.recordFirmTrouble(FIRM_B);

        assertThat(limiter.currentLimit()).isEqualTo(40);
    }

    @Test
    void aFirmCapRecoversStepwiseWithTimeAloneAndItsEntryDisappearsAtTheFullCap() {
        AimdCapacityLimiter limiter = limiter(10, 40, 40, 20);
        limiter.recordFirmTrouble(FIRM_A);
        assertThat(limiter.currentFirmCap(FIRM_A)).isEqualTo(10);
        assertThat(limiter.penalisedFirmCount()).isEqualTo(1);

        clock.advance(INCREASE_INTERVAL.minusSeconds(1));
        assertThat(limiter.currentFirmCap(FIRM_A)).as("too early").isEqualTo(10);
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.currentFirmCap(FIRM_A)).as("one step per full interval").isEqualTo(15);
        assertThat(limiter.penalisedFirmCount()).isEqualTo(1);

        clock.advance(INCREASE_INTERVAL);
        assertThat(limiter.currentFirmCap(FIRM_A)).isEqualTo(20);
        assertThat(limiter.penalisedFirmCount()).as("back at the full cap, forgotten").isZero();
    }

    @Test
    void anIdlePenalisedFirmRecoversByTimeAloneAndItsEntryDisappears() {
        AimdCapacityLimiter limiter = limiter(10, 40, 40, 20);
        limiter.recordFirmTrouble(FIRM_A);

        clock.advance(INCREASE_INTERVAL.multipliedBy(100));

        assertThat(limiter.currentFirmCap(FIRM_A)).isEqualTo(20);
        assertThat(limiter.penalisedFirmCount()).isZero();
        Permit one = limiter.tryAcquire(FIRM_A, 1).orElseThrow();
        assertThat(one).isNotNull();
    }

    @Test
    void aPenalisedFirmCapRisesForAdmissionsWithoutAnyOfItsOwnSuccesses() {
        AimdCapacityLimiter limiter = limiter(10, 40, 40, 4);
        limiter.recordFirmTrouble(FIRM_A);
        limiter.tryAcquire(FIRM_A, 1).orElseThrow();
        limiter.tryAcquire(FIRM_A, 1).orElseThrow();
        assertThat(limiter.tryAcquire(FIRM_A, 1)).isEmpty();

        clock.advance(INCREASE_INTERVAL);

        assertThat(limiter.tryAcquire(FIRM_A, 1)).as("cap 2 + step 5, limited to the full cap of 4").isPresent();
    }

    @Test
    void recoveredFirmsAreDroppedWhenAnyFeedbackArrivesEvenIfNobodyReadsTheirCap() {
        AimdCapacityLimiter limiter = limiter(10, 40, 40, 20);
        limiter.recordFirmTrouble(FIRM_A);
        clock.advance(INCREASE_INTERVAL);
        limiter.recordFirmTrouble(FIRM_B);
        clock.advance(INCREASE_INTERVAL);
        assertThat(limiter.penalisedFirmCount()).as("A is back at 20 but still listed until feedback runs").isEqualTo(2);

        limiter.recordSuccess(new FirmId("firm-c"));

        assertThat(limiter.penalisedFirmCount()).isEqualTo(1);
        clock.advance(INCREASE_INTERVAL.multipliedBy(3));
        limiter.recordOverload(Duration.ZERO);
        assertThat(limiter.penalisedFirmCount()).isZero();
    }

    @Test
    void aSuccessOfAnotherFirmDoesNotSpeedUpAPenalisedFirm() {
        AimdCapacityLimiter limiter = limiter(10, 40, 40, 20);
        limiter.recordFirmTrouble(FIRM_A);

        clock.advance(INCREASE_INTERVAL.minusSeconds(1));
        limiter.recordSuccess(FIRM_B);

        assertThat(limiter.currentFirmCap(FIRM_A)).isEqualTo(10);
    }

    @Test
    void aFurtherDecreaseStartsFromTheCapTheFirmHasRecoveredTo() {
        AimdCapacityLimiter limiter = limiter(10, 40, 40, 20);
        limiter.recordFirmTrouble(FIRM_A);
        clock.advance(INCREASE_INTERVAL);
        assertThat(limiter.currentFirmCap(FIRM_A)).isEqualTo(15);

        limiter.recordFirmTrouble(FIRM_A);

        assertThat(limiter.currentFirmCap(FIRM_A)).as("halved from 15, not from the stored 10").isEqualTo(7);
    }

    @Test
    void troubleRestartsTheWaitBeforeAFirmCapRecovers() {
        AimdCapacityLimiter limiter = limiter(10, 40, 40, 20);
        limiter.recordFirmTrouble(FIRM_A);
        clock.advance(INCREASE_INTERVAL.minusSeconds(1));
        limiter.recordFirmTrouble(FIRM_A);
        assertThat(limiter.currentFirmCap(FIRM_A)).isEqualTo(5);

        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.currentFirmCap(FIRM_A)).as("the first trouble's interval no longer counts").isEqualTo(5);

        clock.advance(INCREASE_INTERVAL.minusSeconds(1));
        assertThat(limiter.currentFirmCap(FIRM_A)).isEqualTo(10);
    }

    @Test
    void aZeroIncreaseIntervalLetsAFirmCapRecoverImmediately() {
        AimdCapacityLimiter limiter = new AimdCapacityLimiter(
                new AimdConfig(10, 40, 40, 20, 5, Duration.ZERO, 0.5, DECREASE_COOLDOWN), clock);

        limiter.recordFirmTrouble(FIRM_A);

        assertThat(limiter.currentFirmCap(FIRM_A)).isEqualTo(20);
        assertThat(limiter.penalisedFirmCount()).isZero();
    }

    @Test
    void anEnormousElapsedTimeDoesNotOverflowTheRecoveryArithmetic() {
        AimdCapacityLimiter limiter = new AimdCapacityLimiter(
                new AimdConfig(10, 40, 40, 20, 1_000_000, Duration.ofNanos(1), 0.5, DECREASE_COOLDOWN), clock);
        limiter.recordFirmTrouble(FIRM_A);

        clock.advance(Duration.ofDays(365L * 1000));

        assertThat(limiter.currentFirmCap(FIRM_A)).isEqualTo(20);
    }

    // --- fair share of the global limit

    @Test
    void withTwoContendingFirmsAFirmCannotHoldMoreThanHalfTheLimit() {
        AimdCapacityLimiter limiter = limiter(10, 10, 10, 10);

        for (int held = 0; held < 5; held++) {
            assertThat(limiter.tryAcquire(FIRM_A, 2)).as("permit %d", held + 1).isPresent();
        }

        assertThat(limiter.tryAcquire(FIRM_A, 2)).as("a sixth would be above its share of 5").isEmpty();
        assertThat(limiter.hasCapacity()).isTrue();
        for (int held = 0; held < 5; held++) {
            assertThat(limiter.tryAcquire(FIRM_B, 2)).as("the other firm's permit %d", held + 1).isPresent();
        }
        assertThat(limiter.hasCapacity()).isFalse();
    }

    @Test
    void aFirmAloneGetsTheWholeLimit() {
        AimdCapacityLimiter limiter = limiter(10, 10, 10, 10);

        for (int held = 0; held < 10; held++) {
            assertThat(limiter.tryAcquire(FIRM_A, 1)).as("permit %d", held + 1).isPresent();
        }

        assertThat(limiter.tryAcquire(FIRM_A, 1)).isEmpty();
    }

    @Test
    void theShareRoundsUpSoTheSharesTogetherCoverTheLimit() {
        AimdCapacityLimiter limiter = limiter(10, 10, 10, 10);

        for (int held = 0; held < 4; held++) {
            assertThat(limiter.tryAcquire(FIRM_A, 3)).as("ceil(10 / 3) = 4, permit %d", held + 1).isPresent();
        }

        assertThat(limiter.tryAcquire(FIRM_A, 3)).isEmpty();
    }

    @Test
    void theShareIsNeverBelowOneEvenWithMoreFirmsThanSlots() {
        AimdCapacityLimiter limiter = limiter(2, 2, 2, 2);

        assertThat(limiter.tryAcquire(FIRM_A, 1_000)).isPresent();
        assertThat(limiter.tryAcquire(FIRM_A, 1_000)).isEmpty();
        assertThat(limiter.tryAcquire(FIRM_B, 1_000)).isPresent();
    }

    @Test
    void theAdaptiveFirmCapStillAppliesWhenItIsBelowTheShare() {
        AimdCapacityLimiter limiter = limiter(10, 10, 10, 8);
        limiter.recordFirmTrouble(FIRM_A);
        assertThat(limiter.currentFirmCap(FIRM_A)).isEqualTo(4);

        for (int held = 0; held < 4; held++) {
            assertThat(limiter.tryAcquire(FIRM_A, 1)).isPresent();
        }

        assertThat(limiter.tryAcquire(FIRM_A, 1)).as("share is 10 but the penalised cap is 4").isEmpty();
    }

    @Test
    void aFirmAboveItsShareAfterASecondFirmAppearsGetsNothingNewWhileTheSecondFirmIsAdmitted() {
        AimdCapacityLimiter limiter = limiter(10, 10, 10, 10);
        List<Permit> held = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            held.add(limiter.tryAcquire(FIRM_A, 1).orElseThrow());
        }

        assertThat(limiter.tryAcquire(FIRM_A, 2)).as("8 held, share is now 5").isEmpty();
        assertThat(limiter.tryAcquire(FIRM_B, 2)).isPresent();
        assertThat(limiter.tryAcquire(FIRM_B, 2)).isPresent();
        assertThat(limiter.hasCapacity()).as("the limit of 10 is reached").isFalse();

        held.forEach(Permit::close);
        assertThat(limiter.tryAcquire(FIRM_A, 2)).as("permits already held were never revoked, only drained")
                .isPresent();
    }

    @Test
    void aFirmBackUnderItsShareIsAdmittedAgain() {
        AimdCapacityLimiter limiter = limiter(10, 10, 10, 10);
        List<Permit> held = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            held.add(limiter.tryAcquire(FIRM_A, 1).orElseThrow());
        }
        assertThat(limiter.tryAcquire(FIRM_A, 2)).isEmpty();

        held.get(0).close();
        held.get(1).close();

        assertThat(limiter.tryAcquire(FIRM_A, 2)).as("4 held, share is 5").isPresent();
        assertThat(limiter.tryAcquire(FIRM_A, 2)).as("5 held").isEmpty();
    }

    @Test
    void theShareFollowsTheCurrentLimitAfterAnOverload() {
        AimdCapacityLimiter limiter = limiter(2, 100, 40, 40);
        limiter.recordOverload(Duration.ZERO);
        assertThat(limiter.currentLimit()).isEqualTo(20);

        for (int held = 0; held < 10; held++) {
            assertThat(limiter.tryAcquire(FIRM_A, 2)).isPresent();
        }

        assertThat(limiter.tryAcquire(FIRM_A, 2)).as("half of 20").isEmpty();
    }

    @ParameterizedTest(name = "{0} contending firms")
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void fewerThanOneContendingFirmIsRejected(int contendingFirms) {
        AimdCapacityLimiter limiter = limiter(10, 10, 10, 10);

        assertThatIllegalArgumentException().isThrownBy(() -> limiter.tryAcquire(FIRM_A, contendingFirms));
        assertThat(limiter.tryAcquire(FIRM_A, 1)).as("nothing was taken").isPresent();
    }

    // --- fixed configuration

    @Test
    void aFixedConfigurationNeverAdaptsFirmCapsOrTheGlobalLimit() {
        AimdCapacityLimiter limiter = new AimdCapacityLimiter(AimdConfig.fixed(8, 4), clock);

        for (int burst = 0; burst < 5; burst++) {
            limiter.recordFirmTrouble(FIRM_A);
            limiter.recordFirmTrouble(FIRM_B);
        }

        assertThat(limiter.currentFirmCap(FIRM_A)).isEqualTo(4);
        assertThat(limiter.currentFirmCap(FIRM_B)).isEqualTo(4);
        assertThat(limiter.penalisedFirmCount()).isZero();
        assertThat(limiter.currentLimit()).isEqualTo(8);
        assertThat(limiter.hasCapacity()).isTrue();
    }

    @Test
    void aGlobalOverloadDoesNotTouchFirmCaps() {
        AimdCapacityLimiter limiter = firmLimiter(40, 20);

        limiter.recordOverload(Duration.ZERO);

        assertThat(limiter.currentLimit()).isEqualTo(20);
        assertThat(limiter.currentFirmCap(FIRM_A)).isEqualTo(20);
        assertThat(limiter.penalisedFirmCount()).isZero();
    }

    @Test
    void theGlobalLimitStillGrowsOnSuccessesWhileAFirmIsPenalised() {
        AimdCapacityLimiter limiter = limiter(10, 100, 10, 20);
        limiter.recordFirmTrouble(FIRM_A);

        clock.advance(INCREASE_INTERVAL);
        limiter.recordSuccess(FIRM_B);

        assertThat(limiter.currentLimit()).isEqualTo(15);
    }

    @Test
    void aCeilingNearIntegerMaxValueSaturatesInsteadOfOverflowingToANegativeLimit() {
        AimdCapacityLimiter limiter = new AimdCapacityLimiter(
                new AimdConfig(1, Integer.MAX_VALUE, Integer.MAX_VALUE - 2, 50, 5, INCREASE_INTERVAL, 0.5,
                        DECREASE_COOLDOWN),
                clock);
        clock.advance(INCREASE_INTERVAL);

        limiter.recordSuccess(FIRM_A);

        assertThat(limiter.currentLimit()).isEqualTo(Integer.MAX_VALUE);
        assertThat(limiter.hasCapacity()).isTrue();
        assertThat(limiter.tryAcquire(FIRM_A, 1)).isPresent();
    }

    @Test
    void aFirmCapAndStepNearIntegerMaxValueRecoverToTheFullCapWithoutOverflowing() {
        AimdCapacityLimiter limiter = new AimdCapacityLimiter(
                new AimdConfig(1, Integer.MAX_VALUE, 10, Integer.MAX_VALUE, Integer.MAX_VALUE, INCREASE_INTERVAL, 0.5,
                        DECREASE_COOLDOWN),
                clock);
        limiter.recordFirmTrouble(FIRM_A);
        assertThat(limiter.currentFirmCap(FIRM_A)).isLessThan(Integer.MAX_VALUE).isPositive();

        clock.advance(INCREASE_INTERVAL);

        assertThat(limiter.currentFirmCap(FIRM_A)).isEqualTo(Integer.MAX_VALUE);
        assertThat(limiter.tryAcquire(FIRM_A, 1)).isPresent();
    }
}
