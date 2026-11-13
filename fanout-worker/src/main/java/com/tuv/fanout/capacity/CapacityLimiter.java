package com.tuv.fanout.capacity;

import com.tuv.fanout.domain.FirmId;
import java.util.Optional;

/** Admission control for the downstream system. Never blocks. */
public interface CapacityLimiter extends CapacityFeedback {

    /** Whether any slot is free, regardless of firm. */
    boolean hasCapacity();

    /**
     * Takes one slot for the firm, or returns empty when the global limit, the firm's own cap
     * or its fair share of the global limit is reached.
     *
     * @param contendingFirms how many firms currently have work to dispatch, this one
     *                        included; at least 1
     * @throws IllegalArgumentException if {@code contendingFirms} is less than 1
     */
    Optional<Permit> tryAcquire(FirmId firmId, int contendingFirms);
}
