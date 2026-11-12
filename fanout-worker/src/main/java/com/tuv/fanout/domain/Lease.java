package com.tuv.fanout.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Time-bounded, exclusive ownership of an item.
 *
 * @param token fencing token. Every write made under the lease carries it, so a worker
 *              whose lease expired and was taken over cannot overwrite the new owner.
 */
public record Lease(WorkItem item, long token, Instant expiresAt) {

    public Lease {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
