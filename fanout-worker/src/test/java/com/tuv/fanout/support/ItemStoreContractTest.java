package com.tuv.fanout.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Pins down the {@code ItemStore} contract that the worker's correctness rests on. Any store
 * implementation proves itself by extending this class and supplying a fresh, empty store.
 */
abstract class ItemStoreContractTest {

    private static final FirmId FIRM = new FirmId("firm-a");
    private static final VersionPin PIN = new VersionPin("audit", 7);
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Instant T0 = Instant.parse("2026-11-12T00:00:00Z");

    /** A new, empty store; called once per test. */
    protected abstract InspectableItemStore createStore();

    private InspectableItemStore store;

    @BeforeEach
    void createEmptyStore() {
        store = createStore();
    }

    private static WorkItem item(String engagement) {
        return WorkItem.create(FIRM, new EngagementId(engagement), PIN);
    }

    private static WorkItem itemOf(FirmId firm, String engagement) {
        return WorkItem.create(firm, new EngagementId(engagement), PIN);
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
    void aLeaseReturnedForAFirmBelongsToThatFirm() {
        FirmId other = new FirmId("firm-b");
        store.enqueue(List.of(itemOf(other, "e1"), itemOf(FIRM, "e2"), itemOf(other, "e3")));

        Lease forFirm = store.leaseNext(FIRM, T0, LEASE).orElseThrow();
        Lease forOther = store.leaseNext(other, T0, LEASE).orElseThrow();

        assertThat(forFirm.item().firmId()).isEqualTo(FIRM);
        assertThat(forOther.item().firmId()).isEqualTo(other);
        assertThat(store.leaseNext(FIRM, T0, LEASE)).as("no item of the firm is left, whatever the others hold")
                .isEmpty();
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

    @Test
    void aHandedBackItemKeepsItsKeyAndGetsItsDeliveryRefunded() {
        store.enqueue(List.of(item("e1")));
        ItemId id = item("e1").id();
        store.requeue(leaseAt(T0), RequeueReason.OUTCOME_UNKNOWN, T0);
        store.requeue(leaseAt(T0), RequeueReason.OUTCOME_UNKNOWN, T0);
        store.requeue(leaseAt(T0), RequeueReason.OUTCOME_UNKNOWN, T0);
        assertThat(store.itemOf(id).deliveryCount()).isEqualTo(3);

        store.requeue(leaseAt(T0), RequeueReason.HANDED_BACK, T0);
        assertThat(store.itemOf(id)).extracting(WorkItem::attemptNumber, WorkItem::deliveryCount)
                .as("4 deliveries, one refunded").containsExactly(1, 3);

        for (int restart = 0; restart < 5; restart++) {
            store.requeue(leaseAt(T0), RequeueReason.HANDED_BACK, T0);
        }
        assertThat(store.itemOf(id).deliveryCount()).as("restarts no longer cost deliveries").isEqualTo(3);
    }

    @Test
    void anItemHandedBackOnItsFirstDeliveryIsNextLeasedAsDeliveryTwo() {
        store.enqueue(List.of(item("e1")));

        store.requeue(leaseAt(T0), RequeueReason.HANDED_BACK, T0);

        assertThat(leaseAt(T0).item().deliveryCount()).isEqualTo(2);
    }

    @Test
    void aHandBackNeverLowersTheStoredCountBelowOne() {
        store.enqueue(List.of(item("e1")));
        ItemId id = item("e1").id();

        for (int restart = 0; restart < 5; restart++) {
            store.requeue(leaseAt(T0), RequeueReason.HANDED_BACK, T0);
            assertThat(store.itemOf(id).deliveryCount()).isEqualTo(1);
        }
    }

    @Test
    void aWriteUnderALeaseThatHasPassedItsExpiryStillSucceedsAsLongAsNobodyTookTheItemOver() {
        store.enqueue(List.of(item("e1"), item("e2"), item("e3")));
        Lease toRenew = leaseAt(T0);
        Lease toFinish = leaseAt(T0);
        Lease toRequeue = leaseAt(T0);
        Instant afterExpiry = T0.plus(LEASE).plusSeconds(1);
        assertThat(store.firmsWithDispatchableItems(afterExpiry)).as("all three leases have expired")
                .containsExactly(FIRM);

        assertThat(store.renew(toRenew, afterExpiry.plus(LEASE))).isTrue();
        assertThat(store.finish(toFinish, ItemStatus.DONE, "applied")).isTrue();
        assertThat(store.requeue(toRequeue, RequeueReason.OUTCOME_UNKNOWN, afterExpiry)).isTrue();

        assertThat(store.statusOf(toRenew.item().id())).isEqualTo(ItemStatus.LEASED);
        assertThat(store.statusOf(toFinish.item().id())).isEqualTo(ItemStatus.DONE);
        assertThat(store.statusOf(toRequeue.item().id())).isEqualTo(ItemStatus.READY);
    }

    @Test
    void aWriteUnderAnExpiredLeaseFailsOnceTheItemWasTakenOver() {
        store.enqueue(List.of(item("e1")));
        Lease expired = leaseAt(T0);
        Lease takeover = leaseAt(T0.plus(LEASE));

        assertThat(store.finish(expired, ItemStatus.DONE, "late")).isFalse();
        assertThat(store.statusOf(takeover.item().id())).isEqualTo(ItemStatus.LEASED);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ItemStatus.class, names = {"READY", "LEASED"})
    void finishWithANonTerminalStatusThrowsIllegalArgumentException(ItemStatus nonTerminal) {
        store.enqueue(List.of(item("e1")));
        Lease lease = leaseAt(T0);

        assertThatIllegalArgumentException().isThrownBy(() -> store.finish(lease, nonTerminal, "not final"));

        assertThat(store.statusOf(lease.item().id())).as("nothing was written").isEqualTo(ItemStatus.LEASED);
    }
}
