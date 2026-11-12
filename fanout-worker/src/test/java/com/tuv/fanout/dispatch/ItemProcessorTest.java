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
import com.tuv.fanout.domain.VersionPin;
import com.tuv.fanout.domain.WorkItem;
import com.tuv.fanout.port.ApplyCommand;
import com.tuv.fanout.port.OfferVerifier;
import com.tuv.fanout.support.FakeEngagementGateway;
import com.tuv.fanout.support.InMemoryItemStore;
import com.tuv.fanout.support.RecordingFeedback;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Random;
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
        RetryPolicy noDelay = new RetryPolicy(MAX_DELIVERIES, Duration.ZERO, Duration.ZERO, new Random(1));
        return new ItemProcessor(store, gateway, offerVerifier, feedback, noDelay, CONFIG, clock);
    }

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

    /** A worker that leased the item, got its submit accepted, then died. */
    private Lease leaseThenCrashAfterSubmit() {
        store.enqueue(List.of(WorkItem.create(FIRM, ENGAGEMENT, PIN)));
        Lease lease = store.leaseNext(FIRM, clock.instant(), Duration.ZERO).orElseThrow();
        gateway.submit(ApplyCommand.forItem(lease.item()));
        return lease;
    }
}
