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
 *   <li>The retry budget limits new submissions. A delivery waits for an attempt it made, or
 *       found under way, for at most the attempt timeout, measured in elapsed time. If no
 *       answer comes, the outcome is unknown: the item is requeued under the same key, and
 *       when the budget is spent it ends as FAILED with detail "outcome unknown". The attempt
 *       may still finish downstream; reconciliation corrects the record then. A status read
 *       on any delivery outranks the budget, so an attempt that is seen to succeed is
 *       recorded as DONE however many deliveries it took.</li>
 *   <li>Deliveries refunded without an attempt are bounded by the deferral limit of the
 *       {@link RetryPolicy}; their wait grows with each deferral and never exceeds the
 *       policy's maximum delay. A real attempt resets the count. At the limit a throttle or
 *       a failed pre-submit step (offer check, lease renewal) ends the item as FAILED, since
 *       that delivery certainly sent nothing. An unreadable status never does: an earlier
 *       delivery may have submitted, so that item keeps being requeued, with a warning.</li>
 *   <li>A 429 is a global signal. Timeouts, unreadable statuses and transport errors on
 *       submit are reported as trouble of the item's firm, not of the downstream system.</li>
 *   <li>A shutdown hand-back refunds its delivery, so restarts do not eat the retry budget.
 *       A submit that fails while the thread is interrupted is a hand-back too, not a
 *       transport error.</li>
 *   <li>A downstream SUCCEEDED is never turned into FAILED: if recording DONE fails, the
 *       item is left to lease expiry and the next delivery polls SUCCEEDED and finishes it.
 *       The success is reported to the capacity feedback only after DONE is recorded, and a
 *       feedback that throws cannot change the outcome.</li>
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
    private final long attemptTimeoutNanos;

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
        this.attemptTimeoutNanos = nanosSaturating(config.attemptTimeout());
    }

    /**
     * Never throws. An interrupt hands the item back so another worker can resume it. The
     * hand-back is made first and the interrupt flag restored afterwards, so a store client
     * that fails fast on an interrupted thread is not sabotaged.
     */
    public void process(Lease lease) {
        try {
            run(lease);
        } catch (InterruptedException e) {
            handBack(lease);
            Thread.currentThread().interrupt();
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
            if (Thread.interrupted()) {
                LOG.log(Level.INFO, "Submit for item {0} was cut short by a shutdown; handing it back", item.id());
                handBack(lease);
                Thread.currentThread().interrupt();
                return;
            }
            LOG.log(Level.WARNING, "Submit failed for item " + item.id() + "; outcome unknown", e);
            capacityFeedback.recordFirmTrouble(lease.item().firmId());
            retryOrFail(lease, RequeueReason.OUTCOME_UNKNOWN, "submit failed: transport error");
            return;
        }
        switch (result) {
            case SubmitResult.Accepted accepted -> awaitOutcome(lease, new StatusReads(lease), true);
            case SubmitResult.Throttled throttled -> deferForCapacity(lease, throttled.retryAfter());
            case SubmitResult.Rejected rejected -> finishRejected(lease, rejected);
        }
    }

    /** A version that no longer applies is a skip, like a changed offer; any other refusal fails. */
    private void finishRejected(Lease lease, SubmitResult.Rejected rejected) {
        switch (rejected.kind()) {
            case VERSION_NOT_APPLICABLE -> store.finish(lease, ItemStatus.SKIPPED_OFFER_CHANGED,
                    "downstream says " + lease.item().pin() + " no longer applies: " + rejected.reason());
            case OTHER -> store.finish(lease, ItemStatus.FAILED, "rejected: " + rejected.reason());
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
            requeueNotAttempted(lease, true);
            return Optional.empty();
        }
    }

    /**
     * Waits by elapsed monotonic time, not by the injected clock: the waits are real sleeps,
     * so a wall-clock step would otherwise end the wait early or stretch it. The injected
     * clock is for the instants the store sees (lease expiry, requeue times).
     *
     * @param submitted whether this delivery made a submit call; only then may the item
     *                  count as already under way downstream
     */
    private void awaitOutcome(Lease lease, StatusReads reads, boolean submitted) throws InterruptedException {
        long startedAt = System.nanoTime();
        while (System.nanoTime() - startedAt < attemptTimeoutNanos) {
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
        capacityFeedback.recordFirmTrouble(lease.item().firmId());
        retryOrFail(lease, RequeueReason.OUTCOME_UNKNOWN,
                "outcome unknown: none within " + config.attemptTimeout());
    }

    /**
     * An unreadable status is not evidence about the attempt. Without a submit in this
     * delivery nothing was attempted, so the delivery is refunded. After a submit the
     * outcome is unknown and the key is kept, but the item is never failed for it: the
     * next delivery polls again. The refund is exempt from the deferral limit's FAILED:
     * an earlier delivery may have submitted, so the item keeps being requeued with a
     * growing, capped backoff and a warning once the limit is passed.
     */
    private void giveUpOnUnreadableStatus(Lease lease, boolean submitted) {
        capacityFeedback.recordFirmTrouble(lease.item().firmId());
        if (submitted) {
            requeueWithBackoff(lease, RequeueReason.OUTCOME_UNKNOWN);
        } else {
            requeueNotAttempted(lease, false);
        }
    }

    /** @return whether the status was final for this delivery; {@code false} means keep waiting */
    private boolean settle(Lease lease, AttemptStatus status) {
        switch (status) {
            case IN_PROGRESS -> {
                return false;
            }
            case SUCCEEDED -> {
                if (finishDone(lease)) {
                    reportSuccess(lease);
                }
            }
            case FAILED_RETRYABLE -> retryOrFail(lease, RequeueReason.ATTEMPT_FAILED, "downstream failure");
            case FAILED_PERMANENT -> store.finish(lease, ItemStatus.FAILED, "downstream permanent failure");
            case NOT_FOUND -> retryOrFail(lease, RequeueReason.OUTCOME_UNKNOWN, "submit was lost");
        }
        return true;
    }

    /** @return whether DONE was recorded without the store throwing */
    private boolean finishDone(Lease lease) {
        try {
            store.finish(lease, ItemStatus.DONE, "applied");
            return true;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Item " + lease.item().id() + " succeeded downstream but DONE could not be"
                    + " recorded; leaving it to lease expiry, when the next delivery will finish it", e);
            return false;
        }
    }

    /** Runs after DONE is recorded, so a feedback that throws cannot undo a success that was seen. */
    private void reportSuccess(Lease lease) {
        try {
            capacityFeedback.recordSuccess(lease.item().firmId());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not report the success of item " + lease.item().id()
                    + " to the capacity feedback; the item is done", e);
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
        deferWithoutAttempt(lease, RequeueReason.CAPACITY_DEFERRED, retryAfter, "throttled", true);
    }

    /**
     * The delivery made no attempt: nothing was sent, or the dependency needed to send was down.
     *
     * @param mayFailAtLimit whether passing the deferral limit may end the item as FAILED. That
     *                       is certain-safe only when this delivery knows nothing was ever
     *                       submitted; an unreadable status says nothing about earlier deliveries
     */
    private void requeueNotAttempted(Lease lease, boolean mayFailAtLimit) {
        try {
            deferWithoutAttempt(lease, RequeueReason.NOT_ATTEMPTED, Duration.ZERO, "dependency unavailable",
                    mayFailAtLimit);
        } catch (RuntimeException e) {
            logLeftToLeaseExpiry(lease, e);
        }
    }

    /**
     * Sends the item back with its delivery refunded, which spends no retry budget; the
     * deferral limit is what stops that from going on for ever. The wait grows with the
     * number of deferrals in a row, is at least the downstream system's hint, and is capped
     * at the retry policy's maximum delay so no hint can postpone an item indefinitely.
     */
    private void deferWithoutAttempt(
            Lease lease, RequeueReason reason, Duration hint, String cause, boolean mayFailAtLimit) {
        WorkItem item = lease.item();
        if (retryPolicy.isDeferralLimitReached(item.deferrals())) {
            if (mayFailAtLimit) {
                store.finish(lease, ItemStatus.FAILED, "deferred " + item.deferrals()
                        + " times without being attempted; last cause: " + cause);
                return;
            }
            LOG.log(Level.WARNING, "Item {0} has been deferred {1} times (limit {2}) because {3}; it may already"
                    + " have been submitted, so it is requeued instead of failed",
                    item.id(), item.deferrals(), retryPolicy.maxDeferrals(), cause);
        }
        Duration backoff = retryPolicy.backoff(item.deliveryCount() + item.deferrals());
        Duration delay = hint.compareTo(backoff) > 0 ? hint : backoff;
        if (delay.compareTo(retryPolicy.maxDelay()) > 0) {
            delay = retryPolicy.maxDelay();
        }
        LOG.log(Level.INFO, "Deferring item {0} without an attempt, deferral {1} of {2}; cause: {3}",
                item.id(), item.deferrals() + 1, retryPolicy.maxDeferrals(), cause);
        store.requeue(lease, reason, clock.instant().plus(delay));
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
     * goes back unconditionally and without delay, with its delivery refunded so a restart
     * does not eat the retry budget: the next delivery asks for its status.
     */
    private void handBack(Lease lease) {
        try {
            store.requeue(lease, RequeueReason.HANDED_BACK, clock.instant());
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

    private static long nanosSaturating(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
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
