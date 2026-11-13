package com.tuv.fanout.port;

import com.tuv.fanout.domain.ItemStatus;
import com.tuv.fanout.domain.Lease;
import com.tuv.fanout.domain.RequeueReason;
import java.time.Instant;

/**
 * The worker's view of the item store: writes made under a lease.
 *
 * <p>Every write is compare-and-set on the lease token. It succeeds while the lease is the
 * item's current one, including after its expiry time as long as nobody has taken it over.
 * It returns {@code false}, and changes nothing, once the item was leased again or finished.
 */
public interface LeaseWriter {

    /** Extends the lease. A {@code false} result means the caller must stop working on it. */
    boolean renew(Lease lease, Instant newExpiry);

    /**
     * Moves the item to a terminal status.
     *
     * @param detail a single line of free text for people, at most 500 characters (the
     *               worker truncates longer text with an ellipsis and replaces control
     *               characters, newlines included, with spaces before it gets here)
     * @throws IllegalArgumentException if the status is not terminal
     */
    boolean finish(Lease lease, ItemStatus terminalStatus, String detail);

    /**
     * Returns the item to READY, not dispatchable before {@code notBefore}. The reason
     * decides what happens to the idempotency key and the delivery count; see
     * {@link RequeueReason}.
     */
    boolean requeue(Lease lease, RequeueReason reason, Instant notBefore);
}
