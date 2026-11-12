package com.tuv.fanout.dispatch;

import static com.tuv.fanout.support.FakeEngagementGateway.Behavior.FAIL_PERMANENT;
import static com.tuv.fanout.support.FakeEngagementGateway.Behavior.FAIL_RETRYABLE;
import static com.tuv.fanout.support.FakeEngagementGateway.Behavior.HANG;
import static com.tuv.fanout.support.FakeEngagementGateway.Behavior.LOSE_SUBMIT;
import static com.tuv.fanout.support.FakeEngagementGateway.Behavior.REJECT;
import static com.tuv.fanout.support.FakeEngagementGateway.Behavior.REJECT_VERSION_NOT_APPLICABLE;
import static com.tuv.fanout.support.FakeEngagementGateway.Behavior.THROTTLE;
import static com.tuv.fanout.support.FakeEngagementGateway.Behavior.THROW_ON_SUBMIT;
import static org.assertj.core.api.Assertions.assertThat;

import com.tuv.fanout.capacity.CapacityFeedback;
import com.tuv.fanout.domain.EngagementId;
import com.tuv.fanout.domain.FirmId;
import com.tuv.fanout.domain.ItemId;
import com.tuv.fanout.domain.ItemStatus;
import com.tuv.fanout.domain.Lease;
import com.tuv.fanout.domain.RequeueReason;
import com.tuv.fanout.domain.VersionPin;
import com.tuv.fanout.domain.WorkItem;
import com.tuv.fanout.domain.AttemptId;
import com.tuv.fanout.port.ApplyCommand;
import com.tuv.fanout.port.AttemptStatus;
import com.tuv.fanout.port.EngagementGateway;
import com.tuv.fanout.port.LeaseWriter;
import com.tuv.fanout.port.OfferVerifier;
import com.tuv.fanout.port.SubmitResult;
import com.tuv.fanout.support.FakeEngagementGateway;
import com.tuv.fanout.support.FakeEngagementGateway.Behavior;
import com.tuv.fanout.support.InMemoryItemStore;
import com.tuv.fanout.support.MutableClock;
import com.tuv.fanout.support.RecordingFeedback;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ItemProcessorTest {

    private static final FirmId FIRM = new FirmId("firm-a");
    private static final EngagementId ENGAGEMENT = new EngagementId("e1");
    private static final VersionPin PIN = new VersionPin("audit", 7);
    private static final ItemId ITEM = ItemId.of(FIRM, PIN, ENGAGEMENT);
    private static final int MAX_DELIVERIES = 3;
    private static final Duration GATEWAY_LATENCY = Duration.ofMillis(5);
    private static final DispatcherConfig CONFIG = new DispatcherConfig(
            Duration.ofSeconds(30),
            Duration.ofMillis(2),
            Duration.ofSeconds(5),
            Duration.ofMillis(5),
            Duration.ofSeconds(5));
    /** Only for tests that need an attempt to time out. */
    private static final DispatcherConfig SHORT_TIMEOUT_CONFIG = new DispatcherConfig(
            Duration.ofSeconds(30),
            Duration.ofMillis(2),
            Duration.ofMillis(80),
            Duration.ofMillis(5),
            Duration.ofSeconds(5));

    private final MutableClock clock = new MutableClock();
    private final InMemoryItemStore store = new InMemoryItemStore();
    private final FakeEngagementGateway gateway = new FakeEngagementGateway(GATEWAY_LATENCY);
    private final RecordingFeedback feedback = new RecordingFeedback();

    private ItemProcessor processor(OfferVerifier offerVerifier) {
        return processor(store, gateway, offerVerifier);
    }

    private ItemProcessor processor(LeaseWriter writer, EngagementGateway downstream, OfferVerifier offerVerifier) {
        RetryPolicy noDelay = new RetryPolicy(MAX_DELIVERIES, Duration.ZERO, Duration.ZERO, new Random(1));
        return processor(writer, downstream, offerVerifier, noDelay);
    }

    private ItemProcessor processor(
            LeaseWriter writer, EngagementGateway downstream, OfferVerifier offerVerifier, RetryPolicy retryPolicy) {
        return processor(writer, downstream, offerVerifier, retryPolicy, CONFIG);
    }

    private ItemProcessor processor(
            LeaseWriter writer,
            EngagementGateway downstream,
            OfferVerifier offerVerifier,
            RetryPolicy retryPolicy,
            DispatcherConfig config) {
        return new ItemProcessor(writer, downstream, offerVerifier, feedback, retryPolicy, config, clock);
    }

    /** Backoff of exactly half the capped exponential, so delays are reproducible and strictly growing. */
    private static RetryPolicy growingBackoff() {
        return new RetryPolicy(MAX_DELIVERIES, Duration.ofSeconds(1), Duration.ofMinutes(10), () -> 0L);
    }

    /** Moves the clock to the item's not-before time and delivers it again; @return the wait it had. */
    private Duration waitAndDeliverAgain(ItemProcessor processor) {
        Duration wait = Duration.between(clock.instant(), store.notBeforeOf(ITEM));
        clock.advance(wait);
        deliverAgain(processor);
        return wait;
    }

    /** Leases the already-enqueued item again, as the dispatcher would after a requeue. */
    private void deliverAgain(ItemProcessor processor) {
        processor.process(store.leaseNext(FIRM, clock.instant(), CONFIG.leaseDuration()).orElseThrow());
    }

    private static final OfferVerifier VERIFIER_DOWN = (engagementId, pin) -> {
        throw new IllegalStateException("offer service unreachable");
    };

    private ItemProcessor processor() {
        return processor((engagementId, pin) -> true);
    }

    private Lease leaseItem() {
        store.enqueue(List.of(WorkItem.create(FIRM, ENGAGEMENT, PIN)));
        return store.leaseNext(FIRM, clock.instant(), CONFIG.leaseDuration()).orElseThrow();
    }

    private void deliver(ItemProcessor processor) {
        processor.process(leaseItem());
    }

    @Test
    void marksTheItemDoneWhenTheDownstreamCallSucceeds() {
        deliver(processor());

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.DONE);
        assertThat(gateway.successfulApplies(ENGAGEMENT)).isEqualTo(1);
        assertThat(feedback.successes()).isEqualTo(1);
    }

    @Test
    void aSuccessIsReportedForTheFirmThatOwnsTheItem() {
        deliver(processor());

        assertThat(feedback.successFirms()).containsExactly(FIRM);
    }

    @Test
    void skipsWithoutCallingDownstreamWhenTheOfferHasChanged() {
        deliver(processor((engagementId, pin) -> false));

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.SKIPPED_OFFER_CHANGED);
        assertThat(gateway.submissionCount()).isZero();
    }

    @Test
    void aThrottledItemIsDeferredWithoutSpendingItsRetryBudget() {
        gateway.script(ENGAGEMENT, THROTTLE);

        deliver(processor());

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.READY);
        assertThat(store.itemOf(ITEM).deliveryCount()).isZero();
        assertThat(feedback.overloads()).isEqualTo(1);
        assertThat(feedback.troubleFirms()).as("a 429 is global, not the firm's fault").isEmpty();
    }

    @Test
    void aDefiniteFailureIsRetriedUnderANewIdempotencyKey() {
        gateway.script(ENGAGEMENT, FAIL_RETRYABLE);
        ItemProcessor processor = processor();

        deliver(processor);
        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.READY);
        deliver(processor);

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.DONE);
        assertThat(gateway.submittedAttemptIds(ENGAGEMENT)).doesNotHaveDuplicates().hasSize(2);
        assertThat(gateway.successfulApplies(ENGAGEMENT)).isEqualTo(1);
    }

    @Test
    void aTransportErrorOnSubmitIsRetriedUnderTheSameIdempotencyKey() {
        gateway.script(ENGAGEMENT, THROW_ON_SUBMIT);
        ItemProcessor processor = processor();

        deliver(processor);
        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.READY);
        deliver(processor);

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.DONE);
        assertThat(gateway.submittedAttemptIds(ENGAGEMENT)).hasSize(2).containsOnly(store.itemOf(ITEM).attemptId());
    }

    @Test
    void aSubmitTheDownstreamNeverRecordedIsSentAgainUnderTheSameKey() {
        gateway.script(ENGAGEMENT, LOSE_SUBMIT);
        ItemProcessor processor = processor();

        deliver(processor);
        deliver(processor);

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.DONE);
        assertThat(gateway.submittedAttemptIds(ENGAGEMENT)).hasSize(2).containsOnly(store.itemOf(ITEM).attemptId());
    }

    @Test
    void anAttemptWithNoOutcomeInTimeKeepsItsKeyAndReportsFirmTrouble() {
        gateway.script(ENGAGEMENT, HANG);
        RetryPolicy noDelay = new RetryPolicy(MAX_DELIVERIES, Duration.ZERO, Duration.ZERO, new Random(1));

        deliver(processor(store, gateway, (engagementId, pin) -> true, noDelay, SHORT_TIMEOUT_CONFIG));

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.READY);
        assertThat(store.itemOf(ITEM).attemptNumber()).isEqualTo(1);
        assertThat(feedback.troubleFirms()).containsExactly(FIRM);
        assertThat(feedback.overloads()).as("a timeout is the firm's trouble, not a 429").isZero();
    }

    @Test
    void failsTheItemOnceTheDeliveryBudgetIsSpent() {
        gateway.script(ENGAGEMENT, FAIL_RETRYABLE, FAIL_RETRYABLE, FAIL_RETRYABLE);
        ItemProcessor processor = processor();

        for (int delivery = 0; delivery < MAX_DELIVERIES; delivery++) {
            deliver(processor);
        }

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.FAILED);
        assertThat(store.detailOf(ITEM)).contains("3 deliveries");
        assertThat(gateway.submissionCount()).isEqualTo(MAX_DELIVERIES);
    }

    @Test
    void doesNotRetryAPermanentFailure() {
        gateway.script(ENGAGEMENT, FAIL_PERMANENT);

        deliver(processor());

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.FAILED);
    }

    @Test
    void doesNotRetryARejectedCommand() {
        gateway.script(ENGAGEMENT, REJECT);

        deliver(processor());

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.FAILED);
        assertThat(store.detailOf(ITEM)).contains("malformed command");
    }

    @Test
    void aRejectionBecauseTheVersionNoLongerAppliesEndsAsSkippedNotFailed() {
        gateway.script(ENGAGEMENT, REJECT_VERSION_NOT_APPLICABLE);

        deliver(processor());

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.SKIPPED_OFFER_CHANGED);
        assertThat(store.detailOf(ITEM)).contains("version 7 is withdrawn");
        assertThat(gateway.submissionCount()).as("never retried").isEqualTo(1);
    }

    @Test
    void aClockStepWhileWaitingDoesNotCutTheAttemptTimeoutShort() {
        AtomicInteger reads = new AtomicInteger();
        EngagementGateway steppedClock = new EngagementGateway() {
            @Override
            public SubmitResult submit(ApplyCommand command) {
                return new SubmitResult.Accepted();
            }

            @Override
            public AttemptStatus status(AttemptId attemptId) {
                if (reads.getAndIncrement() == 0) {
                    clock.advance(Duration.ofHours(1));
                    return AttemptStatus.IN_PROGRESS;
                }
                return AttemptStatus.SUCCEEDED;
            }
        };

        deliver(processor(store, steppedClock, (engagementId, pin) -> true));

        assertThat(store.statusOf(ITEM)).as("the wait is measured in elapsed time, not wall-clock time")
                .isEqualTo(ItemStatus.DONE);
        assertThat(feedback.overloads()).isZero();
        assertThat(feedback.troubleFirms()).isEmpty();
    }

    @Test
    void aFailureToRecordSuccessOnTheLastDeliveryNeverFailsTheItemAndTheNextDeliveryFinishesIt() {
        FailingFinish flaky = new FailingFinish(store, ItemStatus.DONE, 1);
        store.enqueue(List.of(WorkItem.create(FIRM, ENGAGEMENT, PIN)));
        for (int crash = 0; crash < MAX_DELIVERIES - 1; crash++) {
            store.leaseNext(FIRM, clock.instant(), Duration.ZERO).orElseThrow();
        }

        deliverAgain(processor(flaky, gateway, (engagementId, pin) -> true));

        assertThat(store.itemOf(ITEM).deliveryCount()).as("this was the last delivery").isEqualTo(MAX_DELIVERIES);
        assertThat(gateway.successfulApplies(ENGAGEMENT)).isEqualTo(1);
        assertThat(store.statusOf(ITEM)).as("left to lease expiry, not failed").isEqualTo(ItemStatus.LEASED);
        assertThat(feedback.successes()).as("nothing was recorded, so nothing is reported").isZero();

        clock.advance(CONFIG.leaseDuration());
        deliverAgain(processor());

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.DONE);
        assertThat(store.itemOf(ITEM).deliveryCount()).as("budget was already spent").isGreaterThan(MAX_DELIVERIES);
        assertThat(gateway.submissionCount()).as("the next delivery polled, it did not resubmit").isEqualTo(1);
    }

    @Test
    void twentyThrottlesInARowLeaveTheItemReadyWithGrowingDelaysAndTheNextOneFailsIt() {
        Behavior[] throttles = new Behavior[21];
        Arrays.fill(throttles, THROTTLE);
        gateway.script(ENGAGEMENT, throttles);
        ItemProcessor processor = processor(store, gateway, (engagementId, pin) -> true, growingBackoff());
        List<Duration> waits = new ArrayList<>();

        deliver(processor);
        for (int deferral = 1; deferral < 20; deferral++) {
            waits.add(waitAndDeliverAgain(processor));
        }

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.READY);
        assertThat(store.itemOf(ITEM).deferrals()).isEqualTo(20);
        assertThat(store.itemOf(ITEM).deliveryCount()).as("no delivery was ever spent").isZero();
        assertThat(waits).as("backoff keeps growing with the deferral count, up to the cap")
                .isSorted().startsWith(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2))
                .allSatisfy(wait -> assertThat(wait).isLessThanOrEqualTo(Duration.ofMinutes(10)));
        assertThat(waits.get(8)).isLessThan(waits.get(9));

        waitAndDeliverAgain(processor);

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.FAILED);
        assertThat(store.detailOf(ITEM)).contains("deferred 20 times without being attempted").contains("throttled");
        assertThat(gateway.successfulApplies(ENGAGEMENT)).isZero();
    }

    @Test
    void aRealAttemptBetweenDeferralsStartsTheDeferralCountOver() {
        Behavior[] script = new Behavior[39];
        Arrays.fill(script, THROTTLE);
        script[19] = THROW_ON_SUBMIT;
        gateway.script(ENGAGEMENT, script);
        ItemProcessor processor = processor(store, gateway, (engagementId, pin) -> true, growingBackoff());

        deliver(processor);
        while (!store.statusOf(ITEM).isTerminal()) {
            waitAndDeliverAgain(processor);
        }

        assertThat(store.statusOf(ITEM)).as("39 deferrals in total, never 20 in a row").isEqualTo(ItemStatus.DONE);
        assertThat(gateway.successfulApplies(ENGAGEMENT)).isEqualTo(1);
    }

    @Test
    void anItemWhoseDependencyStaysDownIsFailedAfterTheDeferralLimitNamingTheCause() {
        ItemProcessor broken = processor(store, gateway, VERIFIER_DOWN, growingBackoff());

        deliver(broken);
        for (int deferral = 1; deferral < 20; deferral++) {
            waitAndDeliverAgain(broken);
        }
        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.READY);
        assertThat(store.itemOf(ITEM).deferrals()).isEqualTo(20);

        waitAndDeliverAgain(broken);

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.FAILED);
        assertThat(store.detailOf(ITEM)).contains("deferred 20 times without being attempted")
                .contains("dependency unavailable");
        assertThat(gateway.submissionCount()).isZero();
    }

    @Test
    void aDeferralLimitOfOneFailsTheSecondDeferralImmediately() {
        gateway.script(ENGAGEMENT, THROTTLE, THROTTLE);
        RetryPolicy strict = new RetryPolicy(MAX_DELIVERIES, Duration.ofSeconds(1), Duration.ofMinutes(10), 1, () -> 0L);
        ItemProcessor processor = processor(store, gateway, (engagementId, pin) -> true, strict);

        deliver(processor);
        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.READY);
        waitAndDeliverAgain(processor);

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.FAILED);
        assertThat(store.detailOf(ITEM)).contains("deferred 1 times");
    }

    @Test
    void aHugeRetryAfterNeverPostponesAnItemBeyondTheMaximumRetryDelay() {
        RetryPolicy policy = new RetryPolicy(MAX_DELIVERIES, Duration.ofSeconds(1), Duration.ofMinutes(10), () -> 0L);
        ItemProcessor processor = processor(store, gateway, (engagementId, pin) -> true, policy);

        List<Duration> hints = List.of(Duration.ofDays(3650), Duration.ofSeconds(Long.MAX_VALUE));
        for (Duration hint : hints) {
            gateway.throttleRetryAfter(hint);
            EngagementId engagement = new EngagementId("huge-" + hint.getSeconds());
            gateway.script(engagement, THROTTLE);
            store.enqueue(List.of(WorkItem.create(FIRM, engagement, PIN)));
            Lease lease = store.leaseNext(FIRM, clock.instant(), CONFIG.leaseDuration()).orElseThrow();
            processor.process(lease);

            ItemId id = lease.item().id();
            assertThat(store.statusOf(id)).isEqualTo(ItemStatus.READY);
            assertThat(store.notBeforeOf(id)).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));
        }
        assertThat(feedback.pauses()).as("the limiter, not the processor, clamps the pause").isEqualTo(hints);
    }

    @Test
    void anItemAcceptedOnceWhoseStatusStaysUnreadableIsNeverFailedByTheDeferralLimit() {
        AtomicBoolean statusDown = new AtomicBoolean(true);
        AtomicInteger submits = new AtomicInteger();
        EngagementGateway downstream = new EngagementGateway() {
            @Override
            public SubmitResult submit(ApplyCommand command) {
                submits.incrementAndGet();
                return new SubmitResult.Accepted();
            }

            @Override
            public AttemptStatus status(AttemptId attemptId) {
                if (statusDown.get()) {
                    throw new IllegalStateException("status endpoint down");
                }
                return AttemptStatus.SUCCEEDED;
            }
        };
        RetryPolicy policy = growingBackoff();
        ItemProcessor processor = processor(store, downstream, (engagementId, pin) -> true, policy);
        interruptedProcess(processor, leaseItem());

        Duration lastWait = Duration.ZERO;
        for (int unreadable = 0; unreadable < policy.maxDeferrals() + 2; unreadable++) {
            lastWait = waitAndDeliverAgain(processor);
            assertThat(store.statusOf(ITEM)).as("unreadable delivery %d", unreadable + 1)
                    .isEqualTo(ItemStatus.READY);
        }

        assertThat(store.itemOf(ITEM).deferrals()).isGreaterThan(policy.maxDeferrals());
        assertThat(lastWait).as("backoff stays capped at the maximum delay").isLessThanOrEqualTo(policy.maxDelay());
        assertThat(store.itemOf(ITEM).deliveryCount()).as("every unreadable delivery refunded").isEqualTo(1);

        statusDown.set(false);
        waitAndDeliverAgain(processor);

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.DONE);
        assertThat(submits.get()).as("it was submitted once, on delivery 1").isEqualTo(1);
    }

    @Test
    void aSubmitThatSurfacesAShutdownInterruptAsARuntimeExceptionHandsTheItemBackWithoutTrouble() {
        EngagementGateway interrupting = new EngagementGateway() {
            @Override
            public SubmitResult submit(ApplyCommand command) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("client wrapped the interrupt");
            }

            @Override
            public AttemptStatus status(AttemptId attemptId) {
                return AttemptStatus.NOT_FOUND;
            }
        };
        store.enqueue(List.of(WorkItem.create(FIRM, ENGAGEMENT, PIN)));
        for (int crash = 0; crash < MAX_DELIVERIES - 1; crash++) {
            store.leaseNext(FIRM, clock.instant(), Duration.ZERO).orElseThrow();
        }
        InterruptSensitiveWriter writer = new InterruptSensitiveWriter(store);

        deliverAgain(processor(writer, interrupting, (engagementId, pin) -> true));

        assertThat(Thread.interrupted()).as("interrupt flag is restored").isTrue();
        assertThat(store.itemOf(ITEM).deliveryCount()).as("the last delivery was refunded by the hand-back").isEqualTo(MAX_DELIVERIES - 1);
        assertThat(store.statusOf(ITEM)).as("handed back, not failed").isEqualTo(ItemStatus.READY);
        assertThat(writer.requeueSawInterruptFlag).isFalse();
        assertThat(feedback.troubleFirms()).isEmpty();
    }

    @Test
    void aFeedbackThatThrowsNeverTurnsASeenSuccessIntoAFailureEvenOnTheLastDelivery() {
        CapacityFeedback throwing = new CapacityFeedback() {
            @Override
            public void recordSuccess(FirmId firmId) {
                throw new IllegalStateException("metrics down");
            }

            @Override
            public void recordOverload(Duration pause) {
            }

            @Override
            public void recordFirmTrouble(FirmId firmId) {
            }
        };
        RetryPolicy noDelay = new RetryPolicy(MAX_DELIVERIES, Duration.ZERO, Duration.ZERO, new Random(1));
        ItemProcessor processor =
                new ItemProcessor(store, gateway, (engagementId, pin) -> true, throwing, noDelay, CONFIG, clock);
        store.enqueue(List.of(WorkItem.create(FIRM, ENGAGEMENT, PIN)));
        for (int crash = 0; crash < MAX_DELIVERIES - 1; crash++) {
            store.leaseNext(FIRM, clock.instant(), Duration.ZERO).orElseThrow();
        }

        deliverAgain(processor);

        assertThat(store.itemOf(ITEM).deliveryCount()).isEqualTo(MAX_DELIVERIES);
        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.DONE);
    }

    @Test
    void aWorkerThatLostItsLeaseLeavesTheItemToTheNewOwner() {
        store.enqueue(List.of(WorkItem.create(FIRM, ENGAGEMENT, PIN)));
        Lease stale = store.leaseNext(FIRM, clock.instant(), Duration.ZERO).orElseThrow();
        Lease current = store.leaseNext(FIRM, clock.instant(), CONFIG.leaseDuration()).orElseThrow();

        processor().process(stale);

        assertThat(gateway.submissionCount()).as("no downstream call under a dead lease").isZero();
        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.LEASED);
        assertThat(store.finish(current, ItemStatus.DONE, "applied")).isTrue();
    }

    @Test
    void anInterruptedWorkerHandsTheItemBackUnderTheSameKey() {
        gateway.script(ENGAGEMENT, HANG);
        Lease lease = leaseItem();

        Thread.currentThread().interrupt();
        processor().process(lease);

        assertThat(Thread.interrupted()).as("interrupt flag is preserved").isTrue();
        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.READY);
        assertThat(store.itemOf(ITEM).attemptId()).isEqualTo(lease.item().attemptId());
    }

    @Test
    void aWorkerInterruptedOnDeliveryOneLeavesTheItemToBeLeasedNextAsDeliveryTwoWhichPolls() {
        gateway.script(ENGAGEMENT, HANG);
        ItemProcessor processor = processor();
        Lease first = leaseItem();

        interruptedProcess(processor, first);
        Lease second = store.leaseNext(FIRM, clock.instant(), CONFIG.leaseDuration()).orElseThrow();

        assertThat(first.item().deliveryCount()).isEqualTo(1);
        assertThat(second.item().deliveryCount()).isEqualTo(2);
        assertThat(gateway.statusCallCount()).isZero();

        interruptedProcess(processor, second);

        assertThat(gateway.statusCallCount()).as("delivery 2 asked what became of delivery 1").isEqualTo(1);
        assertThat(store.itemOf(ITEM).deliveryCount()).as("hand-back on 2 refunds to 1").isEqualTo(1);
    }

    @Test
    void anItemInterruptedOnDeliveryAfterDeliveryStillHasBudgetAndCompletes() {
        AtomicBoolean applied = new AtomicBoolean();
        AtomicInteger submits = new AtomicInteger();
        EngagementGateway downstream = new EngagementGateway() {
            @Override
            public SubmitResult submit(ApplyCommand command) {
                submits.incrementAndGet();
                return new SubmitResult.Accepted();
            }

            @Override
            public AttemptStatus status(AttemptId attemptId) {
                return applied.get() ? AttemptStatus.SUCCEEDED : AttemptStatus.NOT_FOUND;
            }
        };
        ItemProcessor processor = processor(store, downstream, (engagementId, pin) -> true);
        Lease lease = leaseItem();

        for (int restart = 0; restart < 3 * MAX_DELIVERIES; restart++) {
            interruptedProcess(processor, lease);
            assertThat(store.statusOf(ITEM)).as("restart %d", restart + 1).isEqualTo(ItemStatus.READY);
            assertThat(store.itemOf(ITEM).deliveryCount()).as("stored count after restart %d", restart + 1)
                    .isEqualTo(1);
            lease = store.leaseNext(FIRM, clock.instant(), CONFIG.leaseDuration()).orElseThrow();
        }
        applied.set(true);
        processor.process(lease);

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.DONE);
        assertThat(lease.item().deliveryCount()).as("count never grew past 2").isEqualTo(2);
        assertThat(submits.get()).as("each restart re-sent under the same key").isGreaterThan(MAX_DELIVERIES);
    }

    @Test
    void theHandBackIsMadeBeforeTheInterruptFlagIsRestored() {
        gateway.script(ENGAGEMENT, HANG);
        InterruptSensitiveWriter writer = new InterruptSensitiveWriter(store);

        interruptedProcess(processor(writer, gateway, (engagementId, pin) -> true), leaseItem());

        assertThat(writer.requeueSawInterruptFlag).as("a fail-fast store would have refused").isFalse();
        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.READY);
    }

    /** Runs the processor on the current thread with its interrupt flag set; clears the flag after. */
    private static void interruptedProcess(ItemProcessor processor, Lease lease) {
        Thread.currentThread().interrupt();
        processor.process(lease);
        assertThat(Thread.interrupted()).as("interrupt flag is restored").isTrue();
    }

    @Test
    void aRedeliveryAdoptsAnAttemptThatAlreadySucceededInsteadOfSubmittingAgain() throws InterruptedException {
        Lease crashed = leaseThenCrashAfterSubmit();
        Thread.sleep(GATEWAY_LATENCY.plusMillis(5));

        deliver(processor());

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.DONE);
        assertThat(gateway.submittedAttemptIds(ENGAGEMENT)).containsExactly(crashed.item().attemptId());
    }

    @Test
    void aRedeliveryKeepsWaitingForARunningAttemptEvenIfTheOfferChangedSince() {
        leaseThenCrashAfterSubmit();

        deliver(processor((engagementId, pin) -> false));

        assertThat(store.statusOf(ITEM)).as("the attempt was already under way").isEqualTo(ItemStatus.DONE);
        assertThat(gateway.submissionCount()).isEqualTo(1);
    }

    @Test
    void crashesDoNotFailAnItemWhoseAttemptSucceeded() throws InterruptedException {
        leaseThenCrashAfterSubmit();
        for (int crash = 0; crash < MAX_DELIVERIES; crash++) {
            store.leaseNext(FIRM, clock.instant(), Duration.ZERO).orElseThrow();
        }
        Thread.sleep(GATEWAY_LATENCY.plusMillis(5));

        deliver(processor());

        assertThat(store.itemOf(ITEM).deliveryCount()).isGreaterThan(MAX_DELIVERIES);
        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.DONE);
    }

    @Test
    void crashesBeforeAnySubmitEventuallyFailTheItemWithoutCallingDownstream() {
        store.enqueue(List.of(WorkItem.create(FIRM, ENGAGEMENT, PIN)));
        for (int crash = 0; crash < MAX_DELIVERIES; crash++) {
            store.leaseNext(FIRM, clock.instant(), Duration.ZERO).orElseThrow();
        }

        deliver(processor());

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.FAILED);
        assertThat(gateway.submissionCount()).isZero();
    }

    @Test
    void anOfferVerifierThatKeepsFailingNeverSpendsTheDeliveryBudget() {
        ItemProcessor broken = processor(VERIFIER_DOWN);

        deliver(broken);
        deliverAgain(broken);
        deliverAgain(broken);

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.READY);
        assertThat(store.itemOf(ITEM).deliveryCount()).as("every delivery refunded").isZero();
        assertThat(gateway.submissionCount()).isZero();
        assertThat(feedback.overloads()).as("nothing was sent, so no limiter signal").isZero();
        assertThat(feedback.troubleFirms()).isEmpty();

        deliverAgain(processor());

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.DONE);
        assertThat(gateway.submittedAttemptIds(ENGAGEMENT)).containsExactly(store.itemOf(ITEM).attemptId());
    }

    @Test
    void aLeaseRenewalThatFailsBeforeSubmitNeverSpendsTheDeliveryBudget() {
        FailingRenewals renewals = new FailingRenewals(store, MAX_DELIVERIES);
        ItemProcessor broken = processor(renewals, gateway, (engagementId, pin) -> true);

        deliver(broken);
        deliverAgain(broken);
        deliverAgain(broken);

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.READY);
        assertThat(store.itemOf(ITEM).deliveryCount()).isZero();
        assertThat(gateway.submissionCount()).isZero();
        assertThat(feedback.overloads()).isZero();
        assertThat(feedback.troubleFirms()).isEmpty();

        deliverAgain(broken);

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.DONE);
        assertThat(gateway.submissionCount()).isEqualTo(1);
    }

    @Test
    void aTransportErrorOnSubmitReportsFirmTroubleOncePerFailureAndEndsAsATransportFailure() {
        gateway.script(ENGAGEMENT, THROW_ON_SUBMIT, THROW_ON_SUBMIT, THROW_ON_SUBMIT);
        ItemProcessor processor = processor();

        deliver(processor);
        assertThat(feedback.troubleFirms()).hasSize(1);
        deliverAgain(processor);
        assertThat(feedback.troubleFirms()).hasSize(2);
        deliverAgain(processor);

        assertThat(feedback.troubleFirms()).containsExactly(FIRM, FIRM, FIRM);
        assertThat(feedback.overloads()).isZero();
        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.FAILED);
        assertThat(store.detailOf(ITEM)).contains("submit failed: transport error").contains("3 deliveries");
        assertThat(gateway.submittedAttemptIds(ENGAGEMENT)).hasSize(3).containsOnly(store.itemOf(ITEM).attemptId());
    }

    @Test
    void aStatusEndpointThatStaysDownReleasesTheItemAfterThreeReadsNotAfterTheAttemptTimeout() {
        gateway.failStatusAlways();

        deliver(processor());

        assertThat(gateway.statusCallCount()).isEqualTo(3);
        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.READY);
        assertThat(store.itemOf(ITEM).attemptNumber()).isEqualTo(1);
        assertThat(store.itemOf(ITEM).deliveryCount()).as("a submit was made, so no refund").isEqualTo(1);
        assertThat(feedback.troubleFirms()).containsExactly(FIRM);
        assertThat(feedback.overloads()).isZero();
        assertThat(gateway.submittedAttemptIds(ENGAGEMENT)).containsExactly(store.itemOf(ITEM).attemptId());
    }

    @Test
    void anUnreadableStatusNeverFailsAnItemSubmittedOnItsLastDelivery() {
        AtomicBoolean statusDown = new AtomicBoolean();
        EngagementGateway downAfterSubmit = new EngagementGateway() {
            @Override
            public SubmitResult submit(ApplyCommand command) {
                statusDown.set(true);
                return gateway.submit(command);
            }

            @Override
            public AttemptStatus status(AttemptId attemptId) {
                if (statusDown.get()) {
                    throw new IllegalStateException("status endpoint down");
                }
                return gateway.status(attemptId);
            }
        };
        ItemProcessor processor = processor(store, downAfterSubmit, (engagementId, pin) -> true);
        store.enqueue(List.of(WorkItem.create(FIRM, ENGAGEMENT, PIN)));
        for (int crash = 0; crash < MAX_DELIVERIES - 1; crash++) {
            store.leaseNext(FIRM, clock.instant(), Duration.ZERO).orElseThrow();
        }

        deliverAgain(processor);

        assertThat(store.itemOf(ITEM).deliveryCount()).isEqualTo(MAX_DELIVERIES);
        assertThat(store.statusOf(ITEM)).as("budget spent, but an unreadable status is no evidence of failure")
                .isEqualTo(ItemStatus.READY);
        assertThat(feedback.troubleFirms()).containsExactly(FIRM);
        assertThat(feedback.overloads()).isZero();

        statusDown.set(false);
        deliverAgain(processor);

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.DONE);
        assertThat(gateway.submissionCount()).as("the next delivery polls, it does not resubmit").isEqualTo(1);
    }

    @Test
    void aRedeliveryWhoseStatusIsUnreadableIsRefundedAndSubmitsNothing() {
        leaseThenCrashAfterSubmit();
        gateway.failStatusAlways();

        deliver(processor());

        assertThat(gateway.statusCallCount()).isEqualTo(3);
        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.READY);
        assertThat(store.itemOf(ITEM).deliveryCount()).as("2nd delivery refunded").isEqualTo(1);
        assertThat(gateway.submissionCount()).as("only the crashed worker's submit").isEqualTo(1);
        assertThat(feedback.troubleFirms()).containsExactly(FIRM);
        assertThat(feedback.overloads()).isZero();
    }

    @Test
    void statusReadsThatFailTwiceAndThenAnswerStillAdoptTheOutcome() {
        gateway.failStatusNext(2);

        deliver(processor());

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.DONE);
        assertThat(feedback.overloads()).isZero();
        assertThat(feedback.troubleFirms()).isEmpty();
        assertThat(gateway.submissionCount()).isEqualTo(1);
    }

    @Test
    void aSuccessfulReadResetsTheCountOfConsecutiveStatusFailures() {
        // Scripted, not timed: two failures, a running answer, two failures, then success.
        AtomicInteger reads = new AtomicInteger();
        EngagementGateway flaky = new EngagementGateway() {
            @Override
            public SubmitResult submit(ApplyCommand command) {
                return new SubmitResult.Accepted();
            }

            @Override
            public AttemptStatus status(AttemptId attemptId) {
                int read = reads.getAndIncrement();
                if (read % 3 != 2) {
                    throw new IllegalStateException("status endpoint down");
                }
                return read < 5 ? AttemptStatus.IN_PROGRESS : AttemptStatus.SUCCEEDED;
            }
        };

        deliver(processor(store, flaky, (engagementId, pin) -> true));

        assertThat(reads.get()).as("four failed reads in total, never three in a row").isEqualTo(6);
        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.DONE);
        assertThat(feedback.overloads()).isZero();
        assertThat(feedback.troubleFirms()).isEmpty();
    }

    @Test
    void aRedeliveryWhoseStatusIsUnreadableOnceAndThenNotFoundSubmitsAndCompletes() {
        gateway.script(ENGAGEMENT, LOSE_SUBMIT);
        leaseThenCrashAfterSubmit();
        gateway.failStatusNext(1);

        deliver(processor());

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.DONE);
        assertThat(gateway.submittedAttemptIds(ENGAGEMENT)).hasSize(2).containsOnly(store.itemOf(ITEM).attemptId());
        assertThat(gateway.successfulApplies(ENGAGEMENT)).isEqualTo(1);
    }

    /** Fails the first {@code failures} renewals, as a store blip would, then behaves. */
    private static final class FailingRenewals implements LeaseWriter {

        private final LeaseWriter delegate;
        private final AtomicInteger remainingFailures;

        private FailingRenewals(LeaseWriter delegate, int failures) {
            this.delegate = delegate;
            this.remainingFailures = new AtomicInteger(failures);
        }

        @Override
        public boolean renew(Lease lease, Instant newExpiry) {
            if (remainingFailures.getAndDecrement() > 0) {
                throw new IllegalStateException("store unavailable");
            }
            return delegate.renew(lease, newExpiry);
        }

        @Override
        public boolean finish(Lease lease, ItemStatus terminalStatus, String detail) {
            return delegate.finish(lease, terminalStatus, detail);
        }

        @Override
        public boolean requeue(Lease lease, RequeueReason reason, Instant notBefore) {
            return delegate.requeue(lease, reason, notBefore);
        }
    }

    /** Fails the first {@code failures} finishes to {@code status}, as a store blip would, then behaves. */
    private static final class FailingFinish implements LeaseWriter {

        private final LeaseWriter delegate;
        private final ItemStatus status;
        private final AtomicInteger remainingFailures;

        private FailingFinish(LeaseWriter delegate, ItemStatus status, int failures) {
            this.delegate = delegate;
            this.status = status;
            this.remainingFailures = new AtomicInteger(failures);
        }

        @Override
        public boolean renew(Lease lease, Instant newExpiry) {
            return delegate.renew(lease, newExpiry);
        }

        @Override
        public boolean finish(Lease lease, ItemStatus terminalStatus, String detail) {
            if (terminalStatus == status && remainingFailures.getAndDecrement() > 0) {
                throw new IllegalStateException("store unavailable");
            }
            return delegate.finish(lease, terminalStatus, detail);
        }

        @Override
        public boolean requeue(Lease lease, RequeueReason reason, Instant notBefore) {
            return delegate.requeue(lease, reason, notBefore);
        }
    }

    /** Refuses {@code requeue} when the calling thread is interrupted, as a fail-fast store client would. */
    private static final class InterruptSensitiveWriter implements LeaseWriter {

        private final LeaseWriter delegate;
        private volatile boolean requeueSawInterruptFlag;

        private InterruptSensitiveWriter(LeaseWriter delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean renew(Lease lease, Instant newExpiry) {
            return delegate.renew(lease, newExpiry);
        }

        @Override
        public boolean finish(Lease lease, ItemStatus terminalStatus, String detail) {
            return delegate.finish(lease, terminalStatus, detail);
        }

        @Override
        public boolean requeue(Lease lease, RequeueReason reason, Instant notBefore) {
            if (Thread.currentThread().isInterrupted()) {
                requeueSawInterruptFlag = true;
                throw new IllegalStateException("interrupted");
            }
            return delegate.requeue(lease, reason, notBefore);
        }
    }

    /** A worker that leased the item, got its submit accepted, then died. */
    private Lease leaseThenCrashAfterSubmit() {
        store.enqueue(List.of(WorkItem.create(FIRM, ENGAGEMENT, PIN)));
        Lease lease = store.leaseNext(FIRM, clock.instant(), Duration.ZERO).orElseThrow();
        gateway.submit(ApplyCommand.forItem(lease.item()));
        return lease;
    }
}
