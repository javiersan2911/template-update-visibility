package com.tuv.fanout.dispatch;

import static com.tuv.fanout.support.FakeEngagementGateway.Behavior.FAIL_PERMANENT;
import static com.tuv.fanout.support.FakeEngagementGateway.Behavior.FAIL_RETRYABLE;
import static com.tuv.fanout.support.FakeEngagementGateway.Behavior.HANG;
import static com.tuv.fanout.support.FakeEngagementGateway.Behavior.LOSE_SUBMIT;
import static com.tuv.fanout.support.FakeEngagementGateway.Behavior.REJECT;
import static com.tuv.fanout.support.FakeEngagementGateway.Behavior.THROTTLE;
import static com.tuv.fanout.support.FakeEngagementGateway.Behavior.THROW_ON_SUBMIT;
import static org.assertj.core.api.Assertions.assertThat;

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
import com.tuv.fanout.support.InMemoryItemStore;
import com.tuv.fanout.support.RecordingFeedback;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ItemProcessorTest {

    private static final FirmId FIRM = new FirmId("firm-a");
    private static final EngagementId ENGAGEMENT = new EngagementId("e1");
    private static final VersionPin PIN = new VersionPin("audit", 7);
    private static final ItemId ITEM = ItemId.of(PIN, ENGAGEMENT);
    private static final int MAX_DELIVERIES = 3;
    private static final Duration GATEWAY_LATENCY = Duration.ofMillis(5);
    private static final DispatcherConfig CONFIG = new DispatcherConfig(
            Duration.ofSeconds(30),
            Duration.ofMillis(2),
            Duration.ofMillis(80),
            Duration.ofMillis(5),
            Duration.ofSeconds(5));

    private final Clock clock = Clock.systemUTC();
    private final InMemoryItemStore store = new InMemoryItemStore();
    private final FakeEngagementGateway gateway = new FakeEngagementGateway(GATEWAY_LATENCY);
    private final RecordingFeedback feedback = new RecordingFeedback();

    private ItemProcessor processor(OfferVerifier offerVerifier) {
        return processor(store, gateway, offerVerifier);
    }

    private ItemProcessor processor(LeaseWriter writer, EngagementGateway downstream, OfferVerifier offerVerifier) {
        RetryPolicy noDelay = new RetryPolicy(MAX_DELIVERIES, Duration.ZERO, Duration.ZERO, new Random(1));
        return new ItemProcessor(writer, downstream, offerVerifier, feedback, noDelay, CONFIG, clock);
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
    void anAttemptWithNoOutcomeInTimeKeepsItsKeyAndSignalsOverload() {
        gateway.script(ENGAGEMENT, HANG);

        deliver(processor());

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.READY);
        assertThat(store.itemOf(ITEM).attemptNumber()).isEqualTo(1);
        assertThat(feedback.overloads()).isEqualTo(1);
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
        assertThat(store.detailOf(ITEM)).contains("version mismatch");
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

        deliverAgain(broken);

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.DONE);
        assertThat(gateway.submissionCount()).isEqualTo(1);
    }

    @Test
    void aTransportErrorOnSubmitSignalsOverloadOncePerFailureAndEndsAsATransportFailure() {
        gateway.script(ENGAGEMENT, THROW_ON_SUBMIT, THROW_ON_SUBMIT, THROW_ON_SUBMIT);
        ItemProcessor processor = processor();

        deliver(processor);
        assertThat(feedback.overloads()).isEqualTo(1);
        deliverAgain(processor);
        assertThat(feedback.overloads()).isEqualTo(2);
        deliverAgain(processor);

        assertThat(feedback.overloads()).isEqualTo(3);
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
        assertThat(feedback.overloads()).isEqualTo(1);
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
        assertThat(feedback.overloads()).isEqualTo(1);

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
        assertThat(feedback.overloads()).isEqualTo(1);
    }

    @Test
    void statusReadsThatFailTwiceAndThenAnswerStillAdoptTheOutcome() {
        gateway.failStatusNext(2);

        deliver(processor());

        assertThat(store.statusOf(ITEM)).isEqualTo(ItemStatus.DONE);
        assertThat(feedback.overloads()).isZero();
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

    /** A worker that leased the item, got its submit accepted, then died. */
    private Lease leaseThenCrashAfterSubmit() {
        store.enqueue(List.of(WorkItem.create(FIRM, ENGAGEMENT, PIN)));
        Lease lease = store.leaseNext(FIRM, clock.instant(), Duration.ZERO).orElseThrow();
        gateway.submit(ApplyCommand.forItem(lease.item()));
        return lease;
    }
}
