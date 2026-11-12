package com.tuv.fanout.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.tuv.fanout.domain.EngagementId;
import com.tuv.fanout.domain.FirmId;
import com.tuv.fanout.domain.ItemId;
import com.tuv.fanout.domain.ItemStatus;
import com.tuv.fanout.domain.Lease;
import com.tuv.fanout.domain.RequeueReason;
import com.tuv.fanout.domain.VersionPin;
import com.tuv.fanout.domain.WorkItem;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Pins down the {@code ItemStore} contract that the worker's correctness rests on. */
class InMemoryItemStoreTest {

    private static final FirmId FIRM = new FirmId("firm-a");
    private static final VersionPin PIN = new VersionPin("audit", 7);
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Instant T0 = Instant.parse("2026-11-12T00:00:00Z");

    private final InMemoryItemStore store = new InMemoryItemStore();

    private static WorkItem item(String engagement) {
        return WorkItem.create(FIRM, new EngagementId(engagement), PIN);
    }

    private Lease leaseAt(Instant now) {
        return store.leaseNext(FIRM, now, LEASE).orElseThrow();
    }

    @Test
    void enqueueIgnoresItemsThatAlreadyExist() {
        assertThat(store.enqueue(List.of(item("e1"), item("e2")))).isEqualTo(2);
        Lease lease = leaseAt(T0);
        store.finish(lease, ItemStatus.DONE, "applied");

        assertThat(store.enqueue(List.of(item("e1"), item("e2"), item("e3")))).isEqualTo(1);

        assertThat(store.statusOf(lease.item().id())).isEqualTo(ItemStatus.DONE);
    }

    @Test
    void anItemIsLeasedToOnlyOneOfManyConcurrentWorkers() throws InterruptedException {
        List<WorkItem> items = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            items.add(item("e" + i));
        }
        store.enqueue(items);
        Set<ItemId> leased = ConcurrentHashMap.newKeySet();
        List<ItemId> duplicates = new ArrayList<>();

        try (ExecutorService workers = Executors.newFixedThreadPool(16)) {
            for (int worker = 0; worker < 16; worker++) {
                workers.execute(() -> {
                    Optional<Lease> lease;
                    while ((lease = store.leaseNext(FIRM, T0, LEASE)).isPresent()) {
                        if (!leased.add(lease.get().item().id())) {
                            synchronized (duplicates) {
                                duplicates.add(lease.get().item().id());
                            }
                        }
                    }
                });
            }
            workers.shutdown();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(duplicates).isEmpty();
        assertThat(leased).hasSize(200);
    }

    @Test
    void anExpiredLeaseIsTakenOverWithTheSameIdempotencyKey() {
        store.enqueue(List.of(item("e1")));
        Lease crashed = leaseAt(T0);

        assertThat(store.firmsWithDispatchableItems(T0.plus(LEASE).minusMillis(1))).isEmpty();
        Lease takeover = leaseAt(T0.plus(LEASE));

        assertThat(takeover.item().attemptId()).isEqualTo(crashed.item().attemptId());
        assertThat(takeover.item().deliveryCount()).isEqualTo(2);
        assertThat(takeover.token()).isNotEqualTo(crashed.token());
    }

    @Test
    void writesUnderASupersededLeaseAreRejected() {
        store.enqueue(List.of(item("e1")));
        Lease stale = leaseAt(T0);
        Lease current = leaseAt(T0.plus(LEASE));

        assertThat(store.renew(stale, T0.plus(LEASE.multipliedBy(3)))).isFalse();
        assertThat(store.finish(stale, ItemStatus.FAILED, "stale")).isFalse();
        assertThat(store.requeue(stale, RequeueReason.ATTEMPT_FAILED, T0)).isFalse();

        assertThat(store.statusOf(current.item().id())).isEqualTo(ItemStatus.LEASED);
        assertThat(store.finish(current, ItemStatus.DONE, "applied")).isTrue();
    }

    @Test
    void aFinishedItemCannotBeFinishedOrRequeuedAgain() {
        store.enqueue(List.of(item("e1")));
        Lease lease = leaseAt(T0);
        store.finish(lease, ItemStatus.DONE, "applied");

        assertThat(store.finish(lease, ItemStatus.FAILED, "late")).isFalse();
        assertThat(store.requeue(lease, RequeueReason.OUTCOME_UNKNOWN, T0)).isFalse();
        assertThat(store.firmsWithDispatchableItems(T0.plus(LEASE))).isEmpty();
    }

    @Test
    void renewingKeepsTheItemOutOfReachOfOtherWorkers() {
        store.enqueue(List.of(item("e1")));
        Lease lease = leaseAt(T0);

        assertThat(store.renew(lease, T0.plus(LEASE.multipliedBy(2)))).isTrue();

        assertThat(store.leaseNext(FIRM, T0.plus(LEASE), LEASE)).isEmpty();
    }

    @Test
    void aRequeuedItemIsHeldBackUntilItsNotBeforeTime() {
        store.enqueue(List.of(item("e1")));
        Instant notBefore = T0.plusSeconds(30);
        store.requeue(leaseAt(T0), RequeueReason.OUTCOME_UNKNOWN, notBefore);

        assertThat(store.firmsWithDispatchableItems(notBefore.minusMillis(1))).isEmpty();
        assertThat(store.firmsWithDispatchableItems(notBefore)).containsExactly(FIRM);
    }

    @Test
    void theRequeueReasonDecidesTheKeyAndTheDeliveryBudget() {
        store.enqueue(List.of(item("e1")));
        ItemId id = item("e1").id();

        store.requeue(leaseAt(T0), RequeueReason.CAPACITY_DEFERRED, T0);
        assertThat(store.itemOf(id)).extracting(WorkItem::attemptNumber, WorkItem::deliveryCount)
                .as("deferred: delivery refunded, key kept").containsExactly(1, 0);

        store.requeue(leaseAt(T0), RequeueReason.OUTCOME_UNKNOWN, T0);
        assertThat(store.itemOf(id)).extracting(WorkItem::attemptNumber, WorkItem::deliveryCount)
                .as("unknown outcome: delivery spent, key kept").containsExactly(1, 1);

        store.requeue(leaseAt(T0), RequeueReason.ATTEMPT_FAILED, T0);
        assertThat(store.itemOf(id)).extracting(WorkItem::attemptNumber, WorkItem::deliveryCount)
                .as("definite failure: delivery spent, new key").containsExactly(2, 2);

        store.requeue(leaseAt(T0), RequeueReason.NOT_ATTEMPTED, T0);
        assertThat(store.itemOf(id)).extracting(WorkItem::attemptNumber, WorkItem::deliveryCount)
                .as("not attempted: delivery refunded, key kept").containsExactly(2, 2);
    }
}
