package com.tuv.fanout.port;

import com.tuv.fanout.domain.FirmId;
import com.tuv.fanout.domain.Lease;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * The dispatcher's view of the item store. An item is <em>dispatchable</em> when it is
 * READY and its not-before time has passed, or when it is LEASED and the lease has expired.
 */
public interface ItemLeasing {

    /** Firms that have at least one dispatchable item at {@code now}. */
    Set<FirmId> firmsWithDispatchableItems(Instant now);

    /**
     * Leases one dispatchable item of the firm to exactly one caller and increments its
     * delivery count. An item taken over from an expired lease keeps its attempt number,
     * and therefore its idempotency key.
     */
    Optional<Lease> leaseNext(FirmId firmId, Instant now, Duration leaseDuration);
}
