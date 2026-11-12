package com.tuv.fanout.dispatch;

import com.tuv.fanout.capacity.CapacityFeedback;
import com.tuv.fanout.domain.ItemStatus;
import com.tuv.fanout.domain.Lease;
import com.tuv.fanout.domain.RequeueReason;
import com.tuv.fanout.domain.WorkItem;
import com.tuv.fanout.port.ApplyCommand;
import com.tuv.fanout.port.AttemptStatus;
import com.tuv.fanout.port.EngagementGateway;
import com.tuv.fanout.port.LeaseWriter;
import com.tuv.fanout.port.OfferVerifier;
import com.tuv.fanout.port.SubmitResult;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Drives one leased item to a terminal status or back to the queue.
 *
 * <p>Rules it holds to:
 * <ul>
 *   <li>The idempotency key changes only after the downstream system reported a definite
 *       failure. In every other case a retry reuses the key, so the engagement is processed
 *       at most once per key.</li>
 *   <li>A redelivered item first asks the downstream system what became of the earlier
 *       attempt. Its answer outranks the retry budget and the offer check, because an
 *       attempt that is running or finished cannot be undone by this worker.</li>
 *   <li>The retry budget limits new submissions, never the wait for one already made.</li>
 * </ul>
 */
public final class ItemProcessor {

    private static final Logger LOG = System.getLogger(ItemProcessor.class.getName());

    private final LeaseWriter store;
    private final EngagementGateway gateway;
    private final OfferVerifier offerVerifier;
    private final CapacityFeedback capacityFeedback;
    private final RetryPolicy retryPolicy;
    private final DispatcherConfig config;
    private final Clock clock;

    public ItemProcessor(
            LeaseWriter store,
            EngagementGateway gateway,
            OfferVerifier offerVerifier,
            CapacityFeedback capacityFeedback,
            RetryPolicy retryPolicy,
            DispatcherConfig config,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.offerVerifier = Objects.requireNonNull(offerVerifier, "offerVerifier");
        this.capacityFeedback = Objects.requireNonNull(capacityFeedback, "capacityFeedback");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Never throws. An interrupt hands the item back so another worker can resume it. */
    public void process(Lease lease) {
        try {
            run(lease);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handBack(lease);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Unexpected failure on item " + lease.item().id(), e);
            retryAfterUnexpectedFailure(lease);
        }
    }

    private void run(Lease lease) throws InterruptedException {
        boolean firstDelivery = lease.item().deliveryCount() == 1;
        AttemptStatus earlierAttempt = firstDelivery ? AttemptStatus.NOT_FOUND : pollStatus(lease);
        if (earlierAttempt == AttemptStatus.NOT_FOUND) {
            startAttempt(lease);
        } else if (!settle(lease, earlierAttempt)) {
            awaitOutcome(lease);
        }
    }

    private void startAttempt(Lease lease) throws InterruptedException {
        WorkItem item = lease.item();
        if (retryPolicy.isExhausted(item.deliveryCount() - 1)) {
            store.finish(lease, ItemStatus.FAILED, "delivery budget spent before an attempt was accepted");
            return;
        }
        if (!offerVerifier.isStillOffered(item.engagementId(), item.pin())) {
            store.finish(lease, ItemStatus.SKIPPED_OFFER_CHANGED, "offer is no longer " + item.pin());
            return;
        }
        if (!renewLease(lease)) {
            return;
        }
        switch (gateway.submit(ApplyCommand.forItem(item))) {
            case SubmitResult.Accepted accepted -> awaitOutcome(lease);
            case SubmitResult.Throttled throttled -> deferForCapacity(lease, throttled.retryAfter());
            case SubmitResult.Rejected rejected ->
                    store.finish(lease, ItemStatus.FAILED, "rejected: " + rejected.reason());
        }
    }

    private void awaitOutcome(Lease lease) throws InterruptedException {
        var deadline = clock.instant().plus(config.attemptTimeout());
        while (clock.instant().isBefore(deadline)) {
            Thread.sleep(config.statusPollInterval());
            if (!renewLease(lease) || settle(lease, pollStatus(lease))) {
                return;
            }
        }
        capacityFeedback.recordOverload(Duration.ZERO);
        retryOrFail(lease, RequeueReason.OUTCOME_UNKNOWN,
                "outcome unknown: none within " + config.attemptTimeout());
    }

    /** @return whether the status was final for this delivery; {@code false} means keep waiting */
    private boolean settle(Lease lease, AttemptStatus status) {
        switch (status) {
            case IN_PROGRESS -> {
                return false;
            }
            case SUCCEEDED -> {
                capacityFeedback.recordSuccess();
                store.finish(lease, ItemStatus.DONE, "applied");
            }
            case FAILED_RETRYABLE -> retryOrFail(lease, RequeueReason.ATTEMPT_FAILED, "downstream failure");
            case FAILED_PERMANENT -> store.finish(lease, ItemStatus.FAILED, "downstream permanent failure");
            case NOT_FOUND -> retryOrFail(lease, RequeueReason.OUTCOME_UNKNOWN, "submit was lost");
        }
        return true;
    }

    /** A failed status read says nothing about the attempt, so it counts as still running. */
    private AttemptStatus pollStatus(Lease lease) {
        try {
            return gateway.status(lease.item().attemptId());
        } catch (RuntimeException e) {
            LOG.log(Level.DEBUG, "Status check failed for item " + lease.item().id(), e);
            return AttemptStatus.IN_PROGRESS;
        }
    }

    private boolean renewLease(Lease lease) {
        boolean renewed = store.renew(lease, clock.instant().plus(config.leaseDuration()));
        if (!renewed) {
            LOG.log(Level.WARNING, "Lost the lease on item {0}; leaving it to the new owner",
                    lease.item().id());
        }
        return renewed;
    }

    private void deferForCapacity(Lease lease, Duration retryAfter) {
        capacityFeedback.recordOverload(retryAfter);
        Duration backoff = retryPolicy.backoff(lease.item().deliveryCount());
        Duration delay = retryAfter.compareTo(backoff) > 0 ? retryAfter : backoff;
        store.requeue(lease, RequeueReason.CAPACITY_DEFERRED, clock.instant().plus(delay));
    }

    private void retryOrFail(Lease lease, RequeueReason reason, String detail) {
        int deliveryCount = lease.item().deliveryCount();
        if (retryPolicy.isExhausted(deliveryCount)) {
            store.finish(lease, ItemStatus.FAILED, detail + " after " + deliveryCount + " deliveries");
            return;
        }
        store.requeue(lease, reason, clock.instant().plus(retryPolicy.backoff(deliveryCount)));
    }

    /**
     * The worker is stopping while the attempt may still be running downstream. The item
     * goes back unconditionally and without delay: the next delivery asks for its status.
     */
    private void handBack(Lease lease) {
        try {
            store.requeue(lease, RequeueReason.OUTCOME_UNKNOWN, clock.instant());
        } catch (RuntimeException e) {
            logLeftToLeaseExpiry(lease, e);
        }
    }

    private void retryAfterUnexpectedFailure(Lease lease) {
        try {
            retryOrFail(lease, RequeueReason.OUTCOME_UNKNOWN, "unexpected worker failure");
        } catch (RuntimeException e) {
            logLeftToLeaseExpiry(lease, e);
        }
    }

    private static void logLeftToLeaseExpiry(Lease lease, RuntimeException cause) {
        LOG.log(Level.WARNING, "Could not requeue item " + lease.item().id()
                + "; it will be redelivered after its lease expires", cause);
    }
}
