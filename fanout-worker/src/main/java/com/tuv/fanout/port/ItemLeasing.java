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
     *
     * <p>Post-condition: a lease that is returned is for an item of {@code firmId}, never of
     * another firm. The dispatcher checks it and refuses to run a lease that breaks it, but
     * it cannot hand that item back, so it stays leased until the lease expires.
     *
     * <p>The dispatcher gives up on a call after {@code DispatcherConfig.callTimeout()}. A
     * call that commits after that deadline leaves its item leased, unrun, until the lease
     * expires, and has also spent one delivery of the item. A store that is persistently
     * slower than the call timeout can therefore exhaust an item's delivery budget without a
     * single attempt. Adapters should enforce a statement timeout shorter than the call
     * timeout that rolls the lease back, so a call the dispatcher abandons has changed nothing.
     */
    Optional<Lease> leaseNext(FirmId firmId, Instant now, Duration leaseDuration);
}
