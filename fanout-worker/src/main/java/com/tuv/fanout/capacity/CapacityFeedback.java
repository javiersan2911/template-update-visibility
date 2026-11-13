package com.tuv.fanout.capacity;

import com.tuv.fanout.domain.FirmId;
import java.time.Duration;

/**
 * Health signals from the downstream system, used to adapt the concurrency limit. A signal
 * that the whole downstream system is struggling (a 429) is global. A sign that only one
 * firm's work is in trouble (its attempts time out, its statuses cannot be read) is scoped
 * to that firm, so one tenant whose engagements hang cannot pin everyone's limit down.
 */
public interface CapacityFeedback {

    /** An attempt of the firm completed successfully. */
    void recordSuccess(FirmId firmId);

    /**
     * The downstream system itself is at capacity (HTTP 429).
     *
     * @param pause how long the downstream system asked callers to stay away; zero when it
     *              gave no hint. No new work is admitted until the pause has passed.
     */
    void recordOverload(Duration pause);

    /**
     * Something went wrong that is attributable to this firm's work: an attempt timed out,
     * statuses could not be read, or a submit failed in transit. It lowers that firm's own
     * cap. Only when several firms report trouble close together is the downstream system
     * itself suspected, and the global limit lowered too.
     */
    void recordFirmTrouble(FirmId firmId);
}
