package com.tuv.fanout.support;

import com.tuv.fanout.domain.FirmId;
import com.tuv.fanout.domain.ItemId;
import com.tuv.fanout.domain.ItemStatus;
import com.tuv.fanout.domain.Lease;
import com.tuv.fanout.domain.RequeueReason;
import com.tuv.fanout.domain.WorkItem;
import com.tuv.fanout.port.ItemStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reference implementation of the {@link ItemStore} contract. A production store would
 * express the same transitions as conditional SQL updates ({@code FOR UPDATE SKIP LOCKED}
 * for leasing, {@code WHERE lease_token = ?} for the fenced writes).
 */
public final class InMemoryItemStore implements ItemStore {

    private final Map<ItemId, Entry> entries = new LinkedHashMap<>();
    private long nextToken = 1;

    @Override
    public synchronized int enqueue(Collection<WorkItem> items) {
        int inserted = 0;
        for (WorkItem item : items) {
            if (entries.putIfAbsent(item.id(), new Entry(item)) == null) {
                inserted++;
            }
        }
        return inserted;
    }

    @Override
    public synchronized Set<FirmId> firmsWithDispatchableItems(Instant now) {
        return entries.values().stream()
                .filter(entry -> entry.isDispatchable(now))
                .map(entry -> entry.item.firmId())
                .collect(Collectors.toSet());
    }

    @Override
    public synchronized Optional<Lease> leaseNext(FirmId firmId, Instant now, Duration leaseDuration) {
        return entries.values().stream()
                .filter(entry -> entry.item.firmId().equals(firmId) && entry.isDispatchable(now))
                .findFirst()
                .map(entry -> entry.lease(nextToken++, now.plus(leaseDuration)));
    }

    @Override
    public synchronized boolean renew(Lease lease, Instant newExpiry) {
        return owned(lease).map(entry -> {
            entry.leaseExpiry = newExpiry;
            return true;
        }).orElse(false);
    }

    @Override
    public synchronized boolean finish(Lease lease, ItemStatus terminalStatus, String detail) {
        if (!terminalStatus.isTerminal()) {
            throw new IllegalArgumentException(terminalStatus + " is not terminal");
        }
        return owned(lease).map(entry -> {
            entry.status = terminalStatus;
            entry.detail = detail;
            return true;
        }).orElse(false);
    }

    @Override
    public synchronized boolean requeue(Lease lease, RequeueReason reason, Instant notBefore) {
        return owned(lease).map(entry -> {
            entry.item = entry.item.requeued(reason);
            entry.status = ItemStatus.READY;
            entry.notBefore = notBefore;
            return true;
        }).orElse(false);
    }

    public synchronized ItemStatus statusOf(ItemId id) {
        return entries.get(id).status;
    }

    public synchronized WorkItem itemOf(ItemId id) {
        return entries.get(id).item;
    }

    /** The instant before which the item is not dispatchable. */
    public synchronized Instant notBeforeOf(ItemId id) {
        return entries.get(id).notBefore;
    }

    public synchronized String detailOf(ItemId id) {
        return entries.get(id).detail;
    }

    public synchronized long count(ItemStatus status) {
        return entries.values().stream().filter(entry -> entry.status == status).count();
    }

    public synchronized long countUnfinished(FirmId firmId) {
        return entries.values().stream()
                .filter(entry -> entry.item.firmId().equals(firmId) && !entry.status.isTerminal())
                .count();
    }

    /** One atomic read, so an item moving between READY and LEASED is never missed. */
    public synchronized long countUnfinished() {
        return entries.values().stream().filter(entry -> !entry.status.isTerminal()).count();
    }

    public synchronized int size() {
        return entries.size();
    }

    private Optional<Entry> owned(Lease lease) {
        return Optional.ofNullable(entries.get(lease.item().id()))
                .filter(entry -> entry.status == ItemStatus.LEASED && entry.leaseToken == lease.token());
    }

    private static final class Entry {

        private WorkItem item;
        private ItemStatus status = ItemStatus.READY;
        private Instant notBefore = Instant.MIN;
        private Instant leaseExpiry;
        private long leaseToken;
        private String detail;

        private Entry(WorkItem item) {
            this.item = item;
        }

        private boolean isDispatchable(Instant now) {
            return switch (status) {
                case READY -> !now.isBefore(notBefore);
                case LEASED -> !now.isBefore(leaseExpiry);
                default -> false;
            };
        }

        private Lease lease(long token, Instant expiry) {
            item = item.delivered();
            status = ItemStatus.LEASED;
            leaseToken = token;
            leaseExpiry = expiry;
            return new Lease(item, token, expiry);
        }
    }
}
