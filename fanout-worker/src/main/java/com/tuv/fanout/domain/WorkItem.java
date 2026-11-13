package com.tuv.fanout.domain;

import java.util.Objects;

/**
 * One engagement to process for one published version.
 *
 * @param attemptNumber ordinal of the current downstream attempt. It only advances after
 *                      the downstream system reports a definite failure, which is the only
 *                      case where a fresh idempotency key is safe.
 * @param deliveryCount how many times the item has been leased. It bounds retries and also
 *                      counts deliveries lost to worker crashes. Deferrals and shutdown
 *                      hand-backs refund theirs, but a hand-back never leaves it below 1.
 * @param deferrals     how many times in a row the item was sent back without an attempt
 *                      (throttled, or a dependency was down). Any real attempt resets it.
 */
public record WorkItem(
        ItemId id,
        FirmId firmId,
        EngagementId engagementId,
        VersionPin pin,
        int attemptNumber,
        int deliveryCount,
        int deferrals) {

    public WorkItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(firmId, "firmId");
        Objects.requireNonNull(engagementId, "engagementId");
        Objects.requireNonNull(pin, "pin");
        if (!id.equals(ItemId.of(firmId, pin, engagementId))) {
            // The attempt id, hence the idempotency key, derives from the id: it must name this item's own firm.
            throw new IllegalArgumentException("item id does not match the item's firm, version pin and engagement");
        }
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attempt number starts at 1");
        }
        if (deliveryCount < 0) {
            throw new IllegalArgumentException("delivery count must not be negative");
        }
        if (deferrals < 0) {
            throw new IllegalArgumentException("deferrals must not be negative");
        }
    }

    public static WorkItem create(FirmId firmId, EngagementId engagementId, VersionPin pin) {
        return new WorkItem(ItemId.of(firmId, pin, engagementId), firmId, engagementId, pin, 1, 0, 0);
    }

    /** Stable for as long as the outcome of the current attempt is unknown. */
    public AttemptId attemptId() {
        return new AttemptId(id + "#" + attemptNumber);
    }

    private static boolean refundsADelivery(RequeueReason reason) {
        return reason == RequeueReason.CAPACITY_DEFERRED || reason == RequeueReason.NOT_ATTEMPTED;
    }

    public WorkItem delivered() {
        return new WorkItem(id, firmId, engagementId, pin, attemptNumber, deliveryCount + 1, deferrals);
    }

    /**
     * The item after going back to the queue. The refunded reasons count as deferrals; a
     * reason that follows a real attempt resets the count; a hand-back leaves it alone.
     *
     * @throws IllegalStateException if a refunding reason is given for an item that was never
     *         leased, since there is no delivery to refund
     */
    public WorkItem requeued(RequeueReason reason) {
        if (deliveryCount == 0 && refundsADelivery(reason)) {
            throw new IllegalStateException("an item that was never leased cannot be requeued with " + reason);
        }
        return switch (reason) {
            case CAPACITY_DEFERRED, NOT_ATTEMPTED ->
                    new WorkItem(id, firmId, engagementId, pin, attemptNumber, deliveryCount - 1, deferrals + 1);
            case OUTCOME_UNKNOWN ->
                    new WorkItem(id, firmId, engagementId, pin, attemptNumber, deliveryCount, 0);
            case HANDED_BACK -> new WorkItem(id, firmId, engagementId, pin, attemptNumber,
                    deliveryCount > 1 ? deliveryCount - 1 : deliveryCount, deferrals);
            case ATTEMPT_FAILED ->
                    new WorkItem(id, firmId, engagementId, pin, attemptNumber + 1, deliveryCount, 0);
        };
    }
}
