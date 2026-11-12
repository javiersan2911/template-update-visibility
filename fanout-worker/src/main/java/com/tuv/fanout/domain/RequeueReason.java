package com.tuv.fanout.domain;

/** Why a leased item goes back to the queue. The reason decides which key the retry uses. */
public enum RequeueReason {

    /**
     * The downstream system refused the work for lack of capacity. Nothing was attempted,
     * so the delivery is refunded and the same idempotency key is kept.
     */
    CAPACITY_DEFERRED,

    /**
     * A dependency (offer check, item store) was unreachable before this delivery submitted
     * anything, and the delivery learned nothing about an earlier attempt. The delivery is
     * refunded and the same idempotency key is kept. Only valid when this delivery itself
     * made no submit call: the worker skips the status poll on delivery 1, which is safe
     * only while a delivery count of 1 means nothing was ever submitted. Counts as a deferral.
     */
    NOT_ATTEMPTED,

    /**
     * The attempt may or may not have taken effect (timeout, transport error). The retry
     * must reuse the same idempotency key so the downstream system can deduplicate it.
     * Resets the deferral count.
     */
    OUTCOME_UNKNOWN,

    /**
     * The downstream system reported a definite, retryable failure. The retry needs a new
     * idempotency key, otherwise deduplication would replay the recorded failure. Resets the
     * deferral count.
     */
    ATTEMPT_FAILED,

    /**
     * The worker is shutting down while the attempt may still be running. The key is kept
     * and the delivery is refunded, so restarts do not eat the retry budget, but the stored
     * count never drops below 1: the next delivery is then at least delivery 2 and polls the
     * status before it does anything else. Dropping to 0 would let the next delivery skip
     * that poll, which is safe only while a count of 1 means nothing was ever submitted.
     */
    HANDED_BACK
}
