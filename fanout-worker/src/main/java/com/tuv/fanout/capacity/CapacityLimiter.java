package com.tuv.fanout.capacity;

import com.tuv.fanout.domain.FirmId;
import java.util.Optional;

/** Admission control for the downstream system. Never blocks. */
public interface CapacityLimiter extends CapacityFeedback {

    /** Whether any slot is free, regardless of firm. */
    boolean hasCapacity();

    /** Takes one slot for the firm, or returns empty when the global or the firm limit is reached. */
    Optional<Permit> tryAcquire(FirmId firmId);
}
