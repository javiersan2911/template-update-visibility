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
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

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
 *   <li>A delivery is refunded ({@link RequeueReason#NOT_ATTEMPTED}) only if it made no
 *       submit call itself. Delivery 1 skips the status poll, which is safe only because a
 *       count of 1 means nothing was ever submitted.</li>
 *   <li>An unreadable status is not a verdict: it never fails an item that was submitted.</li>
 * </ul>
 */
public final class ItemProcessor {

    private static final Logger LOG = System.getLogger(ItemProcessor.class.getName());

    /** Unreadable status reads in a row after which a delivery stops waiting. */
    private static final int MAX_CONSECUTIVE_STATUS_FAILURES = 3;

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
        if (lease.item().deliveryCount() == 1) {
            startAttempt(lease);
            return;
        }
        StatusReads reads = new StatusReads(lease);
        Optional<AttemptStatus> earlierAttempt = reads.next();
        if (earlierAttempt.isEmpty()) {
            awaitOutcome(lease, reads, false);
        } else if (earlierAttempt.get() == AttemptStatus.NOT_FOUND) {
            startAttempt(lease);
        } else if (!settle(lease, earlierAttempt.get())) {
            awaitOutcome(lease, reads, false);
        }
    }

    private void startAttempt(Lease lease) throws InterruptedException {
        WorkItem item = lease.item();
        if (retryPolicy.isExhausted(item.deliveryCount() - 1)) {
            store.finish(lease, ItemStatus.FAILED, "delivery budget spent before an attempt was accepted");
            return;
        }
        Optional<Boolean> offered = beforeSubmit(lease, "offer check",
                () -> offerVerifier.isStillOffered(item.engagementId(), item.pin()));
        if (offered.isEmpty()) {
            return;
        }
        if (!offered.get()) {
            store.finish(lease, ItemStatus.SKIPPED_OFFER_CHANGED, "offer is no longer " + item.pin());
            return;
        }
        if (!beforeSubmit(lease, "lease renewal", () -> renewLease(lease)).orElse(false)) {
            return;
        }
        SubmitResult result;
        try {
            result = gateway.submit(ApplyCommand.forItem(item));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Submit failed for item " + item.id() + "; outcome unknown", e);
            capacityFeedback.recordOverload(Duration.ZERO);
            retryOrFail(lease, RequeueReason.OUTCOME_UNKNOWN, "submit failed: transport error");
            return;
        }
        switch (result) {
            case SubmitResult.Accepted accepted -> awaitOutcome(lease, new StatusReads(lease), true);
            case SubmitResult.Throttled throttled -> deferForCapacity(lease, throttled.retryAfter());
            case SubmitResult.Rejected rejected ->
                    store.finish(lease, ItemStatus.FAILED, "rejected: " + rejected.reason());
        }
    }

    /**
     * Runs a step that happens before this delivery submits anything. If the step throws,
     * nothing was attempted: the item goes back with its delivery refunded.
     *
     * @return the step's result, or empty if it threw and the item was handed back
     */
    private <T> Optional<T> beforeSubmit(Lease lease, String step, Supplier<T> action) {
        try {
            return Optional.of(action.get());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not do the " + step + " for item " + lease.item().id()
                    + "; nothing was submitted, requeueing", e);
            requeueWithBackoff(lease, RequeueReason.NOT_ATTEMPTED);
            return Optional.empty();
        }
    }

    /**
     * @param submitted whether this delivery made a submit call; only then may the item
     *                  count as already under way downstream
     */
    private void awaitOutcome(Lease lease, StatusReads reads, boolean submitted) throws InterruptedException {
        var deadline = clock.instant().plus(config.attemptTimeout());
        while (clock.instant().isBefore(deadline)) {
            Thread.sleep(config.statusPollInterval());
            if (!renewLease(lease)) {
                return;
            }
            Optional<AttemptStatus> status = reads.next();
            if (status.isEmpty()) {
                if (reads.exhausted()) {
                    giveUpOnUnreadableStatus(lease, submitted);
                    return;
                }
            } else if (status.get() == AttemptStatus.NOT_FOUND && !submitted) {
                startAttempt(lease);
                return;
            } else if (settle(lease, status.get())) {
                return;
            }
        }
        capacityFeedback.recordOverload(Duration.ZERO);
        retryOrFail(lease, RequeueReason.OUTCOME_UNKNOWN,
                "outcome unknown: none within " + config.attemptTimeout());
    }

    /**
     * An unreadable status is not evidence about the attempt. Without a submit in this
     * delivery nothing was attempted, so the delivery is refunded. After a submit the
     * outcome is unknown and the key is kept, but the item is never failed for it: the
     * next delivery polls again.
     */
    private void giveUpOnUnreadableStatus(Lease lease, boolean submitted) {
        capacityFeedback.recordOverload(Duration.ZERO);
        requeueWithBackoff(lease, submitted ? RequeueReason.OUTCOME_UNKNOWN : RequeueReason.NOT_ATTEMPTED);
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

    private void requeueWithBackoff(Lease lease, RequeueReason reason) {
        try {
            Instant notBefore = clock.instant().plus(retryPolicy.backoff(lease.item().deliveryCount()));
            store.requeue(lease, reason, notBefore);
        } catch (RuntimeException e) {
            logLeftToLeaseExpiry(lease, e);
        }
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

    /**
     * The status reads of one delivery. A read that throws is logged and counted; a read
     * that answers resets the count, so only consecutive failures add up.
     */
    private final class StatusReads {

        private final Lease lease;
        private int consecutiveFailures;

        private StatusReads(Lease lease) {
            this.lease = lease;
        }

        /** @return the status, or empty if it could not be read */
        Optional<AttemptStatus> next() {
            try {
                AttemptStatus status = gateway.status(lease.item().attemptId());
                consecutiveFailures = 0;
                return Optional.of(status);
            } catch (RuntimeException e) {
                consecutiveFailures++;
                LOG.log(Level.WARNING, "Status read failed for item " + lease.item().id()
                        + " (" + consecutiveFailures + " consecutive)", e);
                return Optional.empty();
            }
        }

        boolean exhausted() {
            return consecutiveFailures >= MAX_CONSECUTIVE_STATUS_FAILURES;
        }
    }
}
