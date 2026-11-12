package com.tuv.fanout.domain;

import java.util.Objects;

/**
 * One engagement to process for one published version.
 *
 * @param attemptNumber ordinal of the current downstream attempt. It only advances after
 *                      the downstream system reports a definite failure, which is the only
 *                      case where a fresh idempotency key is safe.
 * @param deliveryCount how many times the item has been leased. It bounds retries and also
 *                      counts deliveries lost to worker crashes.
 */
public record WorkItem(
        ItemId id,
        FirmId firmId,
        EngagementId engagementId,
        VersionPin pin,
        int attemptNumber,
        int deliveryCount) {

    public WorkItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(firmId, "firmId");
        Objects.requireNonNull(engagementId, "engagementId");
        Objects.requireNonNull(pin, "pin");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attempt number starts at 1");
        }
        if (deliveryCount < 0) {
            throw new IllegalArgumentException("delivery count must not be negative");
        }
    }

    public static WorkItem create(FirmId firmId, EngagementId engagementId, VersionPin pin) {
        return new WorkItem(ItemId.of(pin, engagementId), firmId, engagementId, pin, 1, 0);
    }

    /** Stable for as long as the outcome of the current attempt is unknown. */
    public AttemptId attemptId() {
        return new AttemptId(id + "#" + attemptNumber);
    }

    public WorkItem delivered() {
        return new WorkItem(id, firmId, engagementId, pin, attemptNumber, deliveryCount + 1);
    }

    public WorkItem requeued(RequeueReason reason) {
        return switch (reason) {
            case CAPACITY_DEFERRED ->
                    new WorkItem(id, firmId, engagementId, pin, attemptNumber, deliveryCount - 1);
            case OUTCOME_UNKNOWN -> this;
            case ATTEMPT_FAILED ->
                    new WorkItem(id, firmId, engagementId, pin, attemptNumber + 1, deliveryCount);
        };
    }
}
