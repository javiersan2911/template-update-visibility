package com.tuv.fanout.domain;

/** Why a leased item goes back to the queue. The reason decides which key the retry uses. */
public enum RequeueReason {

    /**
     * The downstream system refused the work for lack of capacity. Nothing was attempted,
     * so the delivery is refunded and the same idempotency key is kept.
     */
    CAPACITY_DEFERRED,

    /**
     * The attempt may or may not have taken effect (timeout, transport error). The retry
     * must reuse the same idempotency key so the downstream system can deduplicate it.
     */
    OUTCOME_UNKNOWN,

    /**
     * The downstream system reported a definite, retryable failure. The retry needs a new
     * idempotency key, otherwise deduplication would replay the recorded failure.
     */
    ATTEMPT_FAILED
}
