package com.tuv.fanout.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.awaitility.Awaitility.await;

import com.tuv.fanout.capacity.AimdCapacityLimiter;
import com.tuv.fanout.capacity.AimdConfig;
import com.tuv.fanout.domain.EngagementId;
import com.tuv.fanout.domain.FirmId;
import com.tuv.fanout.domain.ItemId;
import com.tuv.fanout.domain.ItemStatus;
import com.tuv.fanout.domain.Lease;
import com.tuv.fanout.domain.VersionPin;
import com.tuv.fanout.port.ApplyCommand;
import com.tuv.fanout.port.FanOutSource.Target;
import com.tuv.fanout.schedule.RoundRobinScheduler;
import com.tuv.fanout.support.FakeEngagementGateway;
import com.tuv.fanout.support.FakeEngagementGateway.Behavior;
import com.tuv.fanout.support.InMemoryItemStore;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** End-to-end behaviour of the worker against a downstream fake that takes time and fails. */
@Timeout(60)
class FanOutDispatcherTest {

    private static final VersionPin PIN = new VersionPin("audit", 7);
    private static final FirmId BIG_FIRM = new FirmId("big");
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final Clock clock = Clock.systemUTC();
    private final InMemoryItemStore store = new InMemoryItemStore();
    private final List<Target> targets = new ArrayList<>();

    private static DispatcherConfig config(Duration leaseDuration, Duration shutdownGrace) {
        return new DispatcherConfig(
                leaseDuration, Duration.ofMillis(2), Duration.ofSeconds(5), Duration.ofMillis(5), shutdownGrace);
    }

    private FanOutDispatcher dispatcher(
            FakeEngagementGateway gateway, AimdCapacityLimiter limiter, DispatcherConfig config) {
        RetryPolicy retryPolicy = new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5), new Random(1));
        ItemProcessor processor = new ItemProcessor(
                store, gateway, (engagementId, pin) -> true, limiter, retryPolicy, config, clock);
        return new FanOutDispatcher(store, new RoundRobinScheduler(), limiter, processor, config, clock);
    }

    private AimdCapacityLimiter fixedLimiter(int globalLimit, int perFirmCap) {
        return new AimdCapacityLimiter(AimdConfig.fixed(globalLimit, perFirmCap), clock);
    }

    private void publish(FirmId firm, int engagements) {
        IntStream.range(0, engagements)
                .mapToObj(i -> new Target(firm, new EngagementId(firm + "-e" + i)))
                .forEach(targets::add);
        new PublishFanOut(pin -> targets.stream(), store, 50).onPublished(PIN);
    }

    private void awaitAllTerminal() {
        await().atMost(TIMEOUT).until(() -> store.countUnfinished() == 0);
    }

    private void assertEveryEngagementAppliedExactlyOnce(FakeEngagementGateway gateway) {
        assertThat(targets).allSatisfy(target ->
                assertThat(gateway.successfulApplies(target.engagementId())).isEqualTo(1));
    }

    @Test
    void processesEveryEngagementExactlyOnceWithinTheCapacityLimits() {
        FakeEngagementGateway gateway = new FakeEngagementGateway(Duration.ofMillis(20));
        publish(BIG_FIRM, 60);
        IntStream.range(0, 5).forEach(i -> publish(new FirmId("small-" + i), 4));

        try (FanOutDispatcher dispatcher =
                dispatcher(gateway, fixedLimiter(8, 4), config(Duration.ofSeconds(30), TIMEOUT))) {
            dispatcher.start();
            awaitAllTerminal();
        }

        assertThat(store.count(ItemStatus.DONE)).isEqualTo(80);
        assertEveryEngagementAppliedExactlyOnce(gateway);
        assertThat(gateway.submissionCount()).as("one downstream call per engagement").isEqualTo(80);
        assertThat(gateway.maxConcurrent()).isBetween(2, 8);
        assertThat(gateway.maxConcurrentFor(BIG_FIRM)).isBetween(1, 4);
    }

    @Test
    void aLargeFirmDoesNotStarveSmallOnes() {
        FakeEngagementGateway gateway = new FakeEngagementGateway(Duration.ofMillis(20));
        publish(BIG_FIRM, 300);
        List<FirmId> smallFirms = IntStream.range(0, 4).mapToObj(i -> new FirmId("small-" + i)).toList();
        smallFirms.forEach(firm -> publish(firm, 3));

        try (FanOutDispatcher dispatcher =
                dispatcher(gateway, fixedLimiter(6, 6), config(Duration.ofSeconds(30), Duration.ZERO))) {
            dispatcher.start();
            await().atMost(TIMEOUT).until(
                    () -> smallFirms.stream().allMatch(firm -> store.countUnfinished(firm) == 0));

            assertThat(store.countUnfinished(BIG_FIRM)).isGreaterThan(200);
        }
    }

    @Test
    void recoversFromFailuresThrottlingAndLostSubmitsWithoutDoubleProcessing() {
        FakeEngagementGateway gateway = new FakeEngagementGateway(Duration.ofMillis(10));
        publish(BIG_FIRM, 30);
        Behavior[] faults = {
            Behavior.FAIL_RETRYABLE, Behavior.THROTTLE, Behavior.THROW_ON_SUBMIT, Behavior.LOSE_SUBMIT
        };
        for (int i = 0; i < 20; i++) {
            gateway.script(targets.get(i).engagementId(), faults[i % faults.length]);
        }
        EngagementId poisoned = targets.get(29).engagementId();
        gateway.script(poisoned, Behavior.FAIL_PERMANENT);

        try (FanOutDispatcher dispatcher =
                dispatcher(gateway, fixedLimiter(5, 5), config(Duration.ofSeconds(30), TIMEOUT))) {
            dispatcher.start();
            awaitAllTerminal();
        }

        assertThat(store.statusOf(ItemId.of(PIN, poisoned))).isEqualTo(ItemStatus.FAILED);
        assertThat(store.count(ItemStatus.DONE)).isEqualTo(29);
        targets.remove(29);
        assertEveryEngagementAppliedExactlyOnce(gateway);
    }

    @Test
    void aStoppedWorkerHandsItsInFlightItemsBackAndASuccessorResumesThem() {
        FakeEngagementGateway gateway = new FakeEngagementGateway(Duration.ofMillis(150));
        publish(BIG_FIRM, 12);

        try (FanOutDispatcher stopping =
                dispatcher(gateway, fixedLimiter(4, 4), config(Duration.ofSeconds(30), Duration.ZERO))) {
            stopping.start();
            await().atMost(TIMEOUT).until(() -> gateway.submissionCount() >= 4);
        }
        await().atMost(TIMEOUT).until(() -> store.count(ItemStatus.LEASED) == 0);
        assertThat(store.count(ItemStatus.READY)).as("nothing waits for a lease to expire").isPositive();

        runToCompletion(gateway, Duration.ofSeconds(30));

        assertAllDoneUnderOneKeyEach(gateway, 12);
    }

    @Test
    void aSuccessorFinishesWhatAKilledWorkerLeftLeased() {
        FakeEngagementGateway gateway = new FakeEngagementGateway(Duration.ofMillis(50));
        Duration lease = Duration.ofMillis(200);
        publish(BIG_FIRM, 12);
        for (int killedMidFlight = 0; killedMidFlight < 4; killedMidFlight++) {
            Lease orphan = store.leaseNext(BIG_FIRM, clock.instant(), lease).orElseThrow();
            gateway.submit(ApplyCommand.forItem(orphan.item()));
        }

        runToCompletion(gateway, lease);

        assertAllDoneUnderOneKeyEach(gateway, 12);
    }

    @Test
    void twoWorkersSharingOneStoreNeverProcessAnEngagementTwice() {
        FakeEngagementGateway gateway = new FakeEngagementGateway(Duration.ofMillis(10));
        publish(BIG_FIRM, 100);
        DispatcherConfig config = config(Duration.ofSeconds(30), TIMEOUT);

        try (FanOutDispatcher first = dispatcher(gateway, fixedLimiter(4, 4), config);
                FanOutDispatcher second = dispatcher(gateway, fixedLimiter(4, 4), config)) {
            first.start();
            second.start();
            awaitAllTerminal();
        }

        assertAllDoneUnderOneKeyEach(gateway, 100);
        assertThat(gateway.submissionCount()).isEqualTo(100);
    }

    @Test
    void cannotBeRestartedOnceClosed() {
        FanOutDispatcher dispatcher = dispatcher(
                new FakeEngagementGateway(Duration.ZERO), fixedLimiter(1, 1), config(Duration.ofSeconds(30), TIMEOUT));
        dispatcher.start();
        dispatcher.close();

        assertThatIllegalStateException().isThrownBy(dispatcher::start);
    }

    private void runToCompletion(FakeEngagementGateway gateway, Duration lease) {
        try (FanOutDispatcher dispatcher = dispatcher(gateway, fixedLimiter(4, 4), config(lease, TIMEOUT))) {
            dispatcher.start();
            awaitAllTerminal();
        }
    }

    private void assertAllDoneUnderOneKeyEach(FakeEngagementGateway gateway, int expected) {
        assertThat(store.count(ItemStatus.DONE)).isEqualTo(expected);
        assertEveryEngagementAppliedExactlyOnce(gateway);
        assertThat(targets).allSatisfy(target ->
                assertThat(gateway.submittedAttemptIds(target.engagementId()))
                        .as("redelivery reuses the idempotency key")
                        .containsOnly(store.itemOf(ItemId.of(PIN, target.engagementId())).attemptId()));
    }

    @Test
    void picksUpWorkPublishedWhileRunning() {
        FakeEngagementGateway gateway = new FakeEngagementGateway(Duration.ofMillis(5));

        try (FanOutDispatcher dispatcher =
                dispatcher(gateway, fixedLimiter(4, 4), config(Duration.ofSeconds(30), TIMEOUT))) {
            dispatcher.start();
            publish(BIG_FIRM, 10);
            awaitAllTerminal();
        }

        assertThat(store.count(ItemStatus.DONE)).isEqualTo(10);
    }
}
