package com.tuv.fanout.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.tuv.fanout.capacity.AimdCapacityLimiter;
import com.tuv.fanout.capacity.AimdConfig;
import com.tuv.fanout.domain.EngagementId;
import com.tuv.fanout.domain.FirmId;
import com.tuv.fanout.domain.ItemId;
import com.tuv.fanout.domain.ItemStatus;
import com.tuv.fanout.domain.Lease;
import com.tuv.fanout.domain.RequeueReason;
import com.tuv.fanout.domain.VersionPin;
import com.tuv.fanout.port.ApplyCommand;
import com.tuv.fanout.port.EngagementGateway;
import com.tuv.fanout.port.FanOutSource.Target;
import com.tuv.fanout.port.ItemLeasing;
import com.tuv.fanout.port.LeaseWriter;
import com.tuv.fanout.schedule.FairScheduler;
import com.tuv.fanout.schedule.RoundRobinScheduler;
import com.tuv.fanout.support.FakeEngagementGateway;
import com.tuv.fanout.support.FakeEngagementGateway.Behavior;
import com.tuv.fanout.support.HangingGateway;
import com.tuv.fanout.support.HangingGateway.Hang;
import com.tuv.fanout.support.InMemoryItemStore;
import com.tuv.fanout.support.LogCapture;
import com.tuv.fanout.support.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** End-to-end behaviour of the worker against a downstream fake that takes time and fails. */
@Timeout(60)
class FanOutDispatcherTest {

    private static final VersionPin PIN = new VersionPin("audit", 7);
    private static final FirmId BIG_FIRM = new FirmId("big");
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(2);

    private final Clock clock = Clock.systemUTC();
    private final InMemoryItemStore store = new InMemoryItemStore();
    private final List<Target> targets = new ArrayList<>();

    private static DispatcherConfig config(Duration leaseDuration, Duration shutdownGrace) {
        return config(leaseDuration, shutdownGrace, callTimeoutFor(leaseDuration));
    }

    /** Generous next to an in-memory call, and the longest the renewal-cycle rule allows for a short lease. */
    private static Duration callTimeoutFor(Duration leaseDuration) {
        Duration longestAllowed = leaseDuration.minus(POLL_INTERVAL).dividedBy(2);
        return longestAllowed.compareTo(Duration.ofSeconds(10)) < 0 ? longestAllowed : Duration.ofSeconds(10);
    }

    private static DispatcherConfig config(Duration leaseDuration, Duration shutdownGrace, Duration callTimeout) {
        return new DispatcherConfig(leaseDuration, POLL_INTERVAL, Duration.ofSeconds(5),
                Duration.ofMillis(5), shutdownGrace, callTimeout);
    }

    private FanOutDispatcher dispatcher(
            EngagementGateway gateway, AimdCapacityLimiter limiter, DispatcherConfig config) {
        return dispatcher(store, gateway, limiter, config);
    }

    private FanOutDispatcher dispatcher(
            ItemLeasing leasing, EngagementGateway gateway, AimdCapacityLimiter limiter, DispatcherConfig config) {
        return dispatcher(leasing, store, gateway, limiter, config);
    }

    private FanOutDispatcher dispatcher(
            ItemLeasing leasing,
            LeaseWriter writer,
            EngagementGateway gateway,
            AimdCapacityLimiter limiter,
            DispatcherConfig config) {
        return dispatcher(leasing, writer, new RoundRobinScheduler(), clock, gateway, limiter, config);
    }

    private FanOutDispatcher dispatcher(
            ItemLeasing leasing,
            LeaseWriter writer,
            FairScheduler scheduler,
            Clock dispatcherClock,
            EngagementGateway gateway,
            AimdCapacityLimiter limiter,
            DispatcherConfig config) {
        RetryPolicy retryPolicy = new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5), new Random(1));
        ItemProcessor processor = new ItemProcessor(
                writer, gateway, (engagementId, pin) -> true, limiter, retryPolicy, config, dispatcherClock);
        return new FanOutDispatcher(leasing, scheduler, limiter, processor, config, dispatcherClock);
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
        assertThat(gateway.maxConcurrent()).as("global limit").isLessThanOrEqualTo(8);
        assertThat(gateway.maxConcurrentFor(BIG_FIRM)).as("per-firm cap").isLessThanOrEqualTo(4);
    }

    @Test
    void withScarceCapacityNoFirmWaitsMoreThanOneTurnPerOtherFirmAndSmallFirmsFinishFirst() {
        // Two slots for four firms, so the order of the turns is what decides who is served.
        // The huge firm sorts first, so a scheduler that does not rotate would give it every slot.
        List<FirmId> firms = List.of(new FirmId("a-huge"), new FirmId("b-large"),
                new FirmId("c-small"), new FirmId("d-tiny"));
        int[] sizes = {100, 30, 6, 2};
        IntStream.range(0, firms.size()).forEach(i -> publish(firms.get(i), sizes[i]));
        FakeEngagementGateway gateway = new FakeEngagementGateway(Duration.ofMillis(3));
        RecordingLeasing leasing = new RecordingLeasing(store);

        try (FanOutDispatcher dispatcher =
                dispatcher(leasing, gateway, fixedLimiter(2, 2), config(Duration.ofSeconds(30), TIMEOUT))) {
            dispatcher.start();
            awaitAllTerminal();
        }

        List<FirmId> order = leasing.leaseOrder();
        assertThat(order).hasSize(138);
        for (FirmId firm : firms) {
            List<Integer> turns = IntStream.range(0, order.size()).filter(i -> order.get(i).equals(firm))
                    .boxed().toList();
            for (int turn = 1; turn < turns.size(); turn++) {
                int othersInBetween = turns.get(turn) - turns.get(turn - 1) - 1;
                assertThat(othersInBetween).as("%s between its turns %d and %d", firm, turn, turn + 1)
                        .isLessThanOrEqualTo(firms.size() - 1);
            }
        }
        int lastTurnOfHuge = order.lastIndexOf(firms.get(0));
        assertThat(lastTurnOfHuge).isEqualTo(order.size() - 1);
        assertThat(order.lastIndexOf(firms.get(1))).as("the large firm is done before the huge one")
                .isLessThan(lastTurnOfHuge);
        assertThat(order.lastIndexOf(firms.get(2))).as("small firms finish early, not after the huge one")
                .isLessThan(order.size() / 4);
        assertThat(order.lastIndexOf(firms.get(3))).isLessThan(order.size() / 4);
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

        assertThat(store.statusOf(ItemId.of(BIG_FIRM, PIN, poisoned))).isEqualTo(ItemStatus.FAILED);
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
        assertThat(store.count(ItemStatus.LEASED)).as("nothing waits for a lease to expire").isZero();
        assertThat(store.count(ItemStatus.READY)).as("everything not done is back in the queue")
                .isEqualTo(store.size() - store.count(ItemStatus.DONE));

        runToCompletion(gateway, Duration.ofSeconds(30));

        assertAllDoneUnderOneKeyEach(gateway, 12);
    }

    @Test
    void closeReturnsOnlyAfterInterruptedWorkersHaveHandedTheirItemsBack() {
        FakeEngagementGateway gateway = new FakeEngagementGateway(Duration.ofHours(1));
        publish(BIG_FIRM, 4);
        LeaseWriter slowHandBack = new SlowRequeue(store, Duration.ofMillis(300));
        FanOutDispatcher dispatcher = dispatcher(
                store, slowHandBack, gateway, fixedLimiter(4, 4), config(Duration.ofSeconds(30), Duration.ZERO));
        dispatcher.start();
        await().atMost(TIMEOUT).until(() -> gateway.submissionCount() >= 4);

        dispatcher.close();

        assertThat(store.count(ItemStatus.LEASED)).isZero();
        assertThat(store.count(ItemStatus.READY)).isEqualTo(4);
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
                        .containsOnly(store.itemOf(ItemId.of(target.firmId(), PIN, target.engagementId())).attemptId()));
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

    @Test
    void aFirmWhoseLeaseFailsDoesNotStallTheOtherFirms() {
        FirmId broken = new FirmId("a-broken");
        FakeEngagementGateway gateway = new FakeEngagementGateway(Duration.ofMillis(5));
        publish(broken, 3);
        List<FirmId> healthy = IntStream.range(0, 3).mapToObj(i -> new FirmId("b-ok-" + i)).toList();
        healthy.forEach(firm -> publish(firm, 4));
        FlakyLeasing leasing = new FlakyLeasing(store, firm -> firm.equals(broken), Integer.MAX_VALUE);
        AimdCapacityLimiter limiter = fixedLimiter(4, 4);

        try (FanOutDispatcher dispatcher =
                dispatcher(leasing, gateway, limiter, config(Duration.ofSeconds(30), TIMEOUT))) {
            dispatcher.start();
            await().atMost(TIMEOUT).until(
                    () -> healthy.stream().allMatch(firm -> store.countUnfinished(firm) == 0));

            publish(new FirmId("c-late"), 3);
            await().atMost(TIMEOUT).until(() -> store.countUnfinished(new FirmId("c-late")) == 0);
        }

        assertThat(store.count(ItemStatus.DONE)).isEqualTo(15);
        assertThat(store.countUnfinished(broken)).isEqualTo(3);
        assertThat(IntStream.range(0, 4).mapToObj(i -> limiter.tryAcquire(BIG_FIRM, 1)))
                .as("every permit was released").allSatisfy(permit -> assertThat(permit).isPresent());
    }

    @Test
    void aFirmThatFailsOnceAndRecoversGetsItsItemsProcessed() {
        FirmId flaky = new FirmId("a-flaky");
        FakeEngagementGateway gateway = new FakeEngagementGateway(Duration.ofMillis(5));
        publish(flaky, 5);
        publish(new FirmId("b-ok"), 5);
        FlakyLeasing leasing = new FlakyLeasing(store, firm -> firm.equals(flaky), 1);

        try (FanOutDispatcher dispatcher =
                dispatcher(leasing, gateway, fixedLimiter(4, 4), config(Duration.ofSeconds(30), TIMEOUT))) {
            dispatcher.start();
            awaitAllTerminal();
        }

        assertThat(store.count(ItemStatus.DONE)).isEqualTo(10);
        assertThat(leasing.failures()).isEqualTo(1);
    }

    @Test
    void aFailingFirmIsNotRetriedUntilItsCoolOffHasPassedOnTheDispatchersClock() {
        FirmId broken = new FirmId("a-broken");
        MutableClock dispatcherClock = new MutableClock();
        publish(broken, 2);
        FlakyLeasing leasing = new FlakyLeasing(store, firm -> true, Integer.MAX_VALUE);

        try (FanOutDispatcher dispatcher = dispatcher(leasing, store, new RoundRobinScheduler(), dispatcherClock,
                new FakeEngagementGateway(Duration.ofMillis(5)), fixedLimiter(4, 4),
                config(Duration.ofSeconds(30), Duration.ZERO))) {
            dispatcher.start();
            await().atMost(TIMEOUT).until(() -> leasing.attempts() == 1);

            passesWithoutTheClockMoving(leasing, 20);
            assertThat(leasing.attempts()).as("cool-off of one idle delay (5 ms) has not passed").isEqualTo(1);

            dispatcherClock.advance(Duration.ofMillis(5));
            await().atMost(TIMEOUT).until(() -> leasing.attempts() == 2);

            passesWithoutTheClockMoving(leasing, 20);
            dispatcherClock.advance(Duration.ofMillis(9));
            passesWithoutTheClockMoving(leasing, 20);
            assertThat(leasing.attempts()).as("the second cool-off doubled to 10 ms; 9 ms is not enough")
                    .isEqualTo(2);

            dispatcherClock.advance(Duration.ofMillis(1));
            await().atMost(TIMEOUT).until(() -> leasing.attempts() == 3);
        }
    }

    /** Lets the dispatcher run {@code passes} more passes; with a frozen clock none of them may lease the firm. */
    private static void passesWithoutTheClockMoving(FlakyLeasing leasing, int passes) {
        int target = leasing.passes() + passes;
        await().atMost(TIMEOUT).until(() -> leasing.passes() >= target);
    }

    @ParameterizedTest(name = "base {0}, failure {1} -> {2}")
    @CsvSource({
        "PT0.005S, 1, PT0.005S",
        "PT0.005S, 2, PT0.01S",
        "PT0.005S, 4, PT0.04S",
        "PT1S, 6, PT32S",
        "PT1S, 7, PT1M",
        "PT0.005S, 1000, PT1M",
        "PT40S, 2, PT1M",
        "PT1M, 3, PT1M",
        "PT2562047788015215H, 2, PT2562047788015215H",
        "PT5M, 1, PT5M",
        "PT5M, 4, PT5M",
        "PT5M, 1000, PT5M",
        "PT90S, 2, PT90S"
    })
    void theBackoffDoublesPerConsecutiveFailureFromTheBaseUpToOneMinute(
            Duration base, int failures, Duration expected) {
        assertThat(FanOutDispatcher.backoffDelay(base, failures)).isEqualTo(expected);
    }

    // ---- the dispatch loop survives, or says why it did not ----

    @Test
    void anErrorOnTheDispatchThreadIsLoggedAtErrorAndTheDispatcherReportsItIsNotRunning() {
        Fatal fatal = new Fatal();
        ItemLeasing dying = new ItemLeasing() {
            @Override
            public Set<FirmId> firmsWithDispatchableItems(Instant now) {
                throw fatal;
            }

            @Override
            public Optional<Lease> leaseNext(FirmId firmId, Instant now, Duration leaseDuration) {
                return Optional.empty();
            }
        };
        try (LogCapture log = LogCapture.of(FanOutDispatcher.class);
                FanOutDispatcher dispatcher = dispatcher(dying, new FakeEngagementGateway(Duration.ZERO),
                        fixedLimiter(1, 1), config(Duration.ofSeconds(30), TIMEOUT))) {
            assertThat(dispatcher.isRunning()).isFalse();
            dispatcher.start();

            // The Error is meant to reach the thread's uncaught-exception handler; Awaitility would rethrow it here.
            await().atMost(TIMEOUT).dontCatchUncaughtExceptions().until(() -> !dispatcher.isRunning());

            assertThat(log.at(Level.SEVERE)).singleElement().satisfies(entry -> {
                assertThat(entry.message()).contains("stopped").contains("simulated VM error");
                assertThat(entry.thrown()).isSameAs(fatal);
            });
        }
    }

    @Test
    void aHealthyDispatcherIsRunningFromStartUntilClose() {
        FanOutDispatcher dispatcher = dispatcher(new FakeEngagementGateway(Duration.ZERO), fixedLimiter(1, 1),
                config(Duration.ofSeconds(30), TIMEOUT));

        dispatcher.start();
        assertThat(dispatcher.isRunning()).isTrue();
        dispatcher.close();

        assertThat(dispatcher.isRunning()).isFalse();
    }

    @Test
    void aLoopThatEndsOnAnInterruptSaysSoAtInfoAndIsNoLongerRunning() {
        AtomicBoolean interrupted = new AtomicBoolean();
        ItemLeasing interruptingOnce = new ItemLeasing() {
            @Override
            public Set<FirmId> firmsWithDispatchableItems(Instant now) {
                if (interrupted.compareAndSet(false, true)) {
                    Thread.currentThread().interrupt();
                }
                return Set.of();
            }

            @Override
            public Optional<Lease> leaseNext(FirmId firmId, Instant now, Duration leaseDuration) {
                return Optional.empty();
            }
        };
        try (LogCapture log = LogCapture.of(FanOutDispatcher.class);
                FanOutDispatcher dispatcher = dispatcher(interruptingOnce, new FakeEngagementGateway(Duration.ZERO),
                        fixedLimiter(1, 1), config(Duration.ofSeconds(30), TIMEOUT))) {
            dispatcher.start();

            await().atMost(TIMEOUT).until(() -> !dispatcher.isRunning());

            assertThat(log.at(Level.INFO)).singleElement()
                    .satisfies(entry -> assertThat(entry.message()).contains("interrupted"));
            assertThat(log.at(Level.SEVERE)).isEmpty();
        }
    }

    // ---- an Error after the permit was taken must not leak it ----

    @Test
    void anErrorFromTheLeaseCallDoesNotLeakThePermit() {
        publish(BIG_FIRM, 1);
        AtomicBoolean thrown = new AtomicBoolean();
        ItemLeasing failingOnce = new ItemLeasing() {
            @Override
            public Set<FirmId> firmsWithDispatchableItems(Instant now) {
                return store.firmsWithDispatchableItems(now);
            }

            @Override
            public Optional<Lease> leaseNext(FirmId firmId, Instant now, Duration leaseDuration) {
                if (thrown.compareAndSet(false, true)) {
                    throw new Fatal();
                }
                return store.leaseNext(firmId, now, leaseDuration);
            }
        };
        AimdCapacityLimiter limiter = fixedLimiter(2, 2);

        try (FanOutDispatcher dispatcher = dispatcher(failingOnce, new FakeEngagementGateway(Duration.ZERO),
                limiter, config(Duration.ofSeconds(30), TIMEOUT))) {
            dispatcher.start();
            await().atMost(TIMEOUT).dontCatchUncaughtExceptions().until(() -> !dispatcher.isRunning());
        }

        assertTheFullLimitIsAvailable(limiter);
    }

    @Test
    void anErrorAfterTheLeaseDoesNotLeakThePermitAndNamesTheLeasedItem() {
        publish(BIG_FIRM, 1);
        ItemId stranded = ItemId.of(BIG_FIRM, PIN, new EngagementId("big-e0"));
        FairScheduler failing = new FairScheduler() {
            @Override
            public List<FirmId> order(Set<FirmId> candidates) {
                return List.copyOf(candidates);
            }

            @Override
            public void served(FirmId firmId) {
                throw new Fatal();
            }
        };
        AimdCapacityLimiter limiter = fixedLimiter(2, 2);

        try (LogCapture log = LogCapture.of(FanOutDispatcher.class);
                FanOutDispatcher dispatcher = dispatcher(store, store, failing, clock,
                        new FakeEngagementGateway(Duration.ZERO), limiter, config(Duration.ofSeconds(30), TIMEOUT))) {
            dispatcher.start();
            await().atMost(TIMEOUT).dontCatchUncaughtExceptions().until(() -> !dispatcher.isRunning());

            assertThat(log.at(Level.WARNING)).extracting(LogCapture.Entry::message)
                    .anySatisfy(message -> assertThat(message).contains(stranded.toString())
                            .contains("redelivered after its lease expires"));
        }

        assertTheFullLimitIsAvailable(limiter);
    }

    private static void assertTheFullLimitIsAvailable(AimdCapacityLimiter limiter) {
        assertThat(IntStream.range(0, 2).mapToObj(i -> limiter.tryAcquire(BIG_FIRM, 1)).toList())
                .as("both permits can still be handed out").allSatisfy(permit -> assertThat(permit).isPresent());
    }

    // ---- an item leased but not started is named ----

    @Test
    void anItemLeasedWhenTheExecutorIsAlreadyShutDownIsNamedAndIsNotAFailedPass() throws InterruptedException {
        publish(BIG_FIRM, 1);
        ItemId stranded = ItemId.of(BIG_FIRM, PIN, new EngagementId("big-e0"));
        GatedLeasing leasing = new GatedLeasing(store, BIG_FIRM, true);
        AimdCapacityLimiter limiter = fixedLimiter(2, 2);
        FanOutDispatcher dispatcher = dispatcher(
                leasing, new FakeEngagementGateway(Duration.ZERO), limiter, config(Duration.ofSeconds(30), TIMEOUT));

        try (LogCapture log = LogCapture.of(FanOutDispatcher.class)) {
            dispatcher.start();
            leasing.awaitEntered();
            Thread closer = Thread.ofPlatform().start(dispatcher::close);
            await().atMost(TIMEOUT).until(() -> !dispatcher.isRunning());
            closer.interrupt(); // close() gives up waiting and shuts the executor down under the running pass
            closer.join();
            leasing.release();
            dispatcher.close(); // waits for the dispatch thread to finish its pass

            assertThat(log.at(Level.WARNING)).extracting(LogCapture.Entry::message)
                    .anySatisfy(message -> assertThat(message).contains(stranded.toString())
                            .contains("redelivered after its lease expires"))
                    .noneSatisfy(message -> assertThat(message).contains("Dispatch pass failed"));
        }
        assertThat(store.statusOf(stranded)).as("only the lease expiry brings it back").isEqualTo(ItemStatus.LEASED);
        assertThat(IntStream.range(0, 2).mapToObj(i -> limiter.tryAcquire(BIG_FIRM, 1)))
                .as("the permit was released").allSatisfy(permit -> assertThat(permit).isPresent());
    }

    @Test
    void anItemLeasedWhoseStartFailsWhileRunningIsNamedAndTheFailureStillSurfacesAsAFailedPass() {
        publish(BIG_FIRM, 1);
        ItemId stranded = ItemId.of(BIG_FIRM, PIN, new EngagementId("big-e0"));
        AtomicBoolean thrown = new AtomicBoolean();
        RoundRobinScheduler real = new RoundRobinScheduler();
        FairScheduler failingOnce = new FairScheduler() {
            @Override
            public List<FirmId> order(Set<FirmId> candidates) {
                return real.order(candidates);
            }

            @Override
            public void served(FirmId firmId) {
                if (thrown.compareAndSet(false, true)) {
                    throw new IllegalStateException("scheduler broke");
                }
                real.served(firmId);
            }
        };

        try (LogCapture log = LogCapture.of(FanOutDispatcher.class);
                FanOutDispatcher dispatcher = dispatcher(store, store, failingOnce, clock,
                        new FakeEngagementGateway(Duration.ZERO), fixedLimiter(2, 2),
                        config(Duration.ofSeconds(30), TIMEOUT))) {
            dispatcher.start();

            await().atMost(TIMEOUT).until(() -> log.at(Level.WARNING).stream()
                    .anyMatch(entry -> entry.message().contains("Dispatch pass failed")));

            assertThat(log.at(Level.WARNING)).extracting(LogCapture.Entry::message)
                    .anySatisfy(message -> assertThat(message).contains(stranded.toString())
                            .contains("redelivered after its lease expires"));
        }
    }

    // ---- close() stops a pass that is already under way ----

    @Test
    void aPassUnderWayStopsStartingFirmsOnceCloseHasBeenCalled() throws InterruptedException {
        publish(new FirmId("a-first"), 1);
        publish(new FirmId("b-second"), 1);
        GatedLeasing leasing = new GatedLeasing(store, new FirmId("a-first"), false);
        FanOutDispatcher dispatcher = dispatcher(
                leasing, new FakeEngagementGateway(Duration.ZERO), fixedLimiter(4, 4),
                config(Duration.ofSeconds(30), TIMEOUT));

        dispatcher.start();
        leasing.awaitEntered();
        Thread closer = Thread.ofPlatform().start(dispatcher::close);
        await().atMost(TIMEOUT).until(() -> !dispatcher.isRunning());
        leasing.release();
        closer.join();

        assertThat(leasing.leaseCalls()).as("the second firm was never offered a slot").containsExactly(
                new FirmId("a-first"));
    }

    // ---- one firm's hung items cannot take the whole pool ----

    @Test
    void aFirmWhoseItemsNeverFinishCannotCrowdAWellBehavedFirmOutOfTheSlots() {
        FirmId hanging = new FirmId("a-hanging");
        FirmId healthy = new FirmId("b-ok");
        publish(hanging, 40);
        publish(healthy, 30);
        FakeEngagementGateway gateway = new FakeEngagementGateway(Duration.ofMillis(200));
        targets.stream().filter(target -> target.firmId().equals(hanging))
                .forEach(target -> gateway.script(target.engagementId(), Behavior.HANG));

        try (FanOutDispatcher dispatcher =
                dispatcher(gateway, fixedLimiter(10, 10), config(Duration.ofSeconds(30), Duration.ZERO))) {
            dispatcher.start();
            await().atMost(TIMEOUT).until(() -> store.countUnfinished(healthy) <= 15);

            assertThat(store.countLeased(hanging)).as("the hanging firm holds its share of 10 and no more")
                    .isEqualTo(5);
            await().atMost(TIMEOUT).until(() -> store.countUnfinished(healthy) == 0);
        }

        // The high-water mark depends on how the scheduler interleaves submits, so only the bounds are
        // asserted: never over the share of 5, and at least two at once. That the firm was not starved is
        // proved by all its items completing while the other firm held its slots.
        assertThat(gateway.maxConcurrentFor(healthy)).as("the healthy firm kept a share of the slots")
                .isBetween(2, 5);
        assertThat(store.count(ItemStatus.DONE)).isEqualTo(30);
    }

    // ---- a lease for another firm is never run ----

    @Test
    void aLeaseForAnotherFirmIsNotRunIsLoggedWithoutTheRecordAndCoolsTheFirmOffWhileOthersAreServed() {
        FirmId victim = new FirmId("a-victim");
        FirmId source = new FirmId("c-source");
        FirmId healthy = new FirmId("b-ok");
        publish(victim, 1);
        publish(healthy, 3);
        publish(source, 1);
        ItemId stolen = ItemId.of(source, PIN, new EngagementId("c-source-e0"));
        ForeignLeasing leasing = new ForeignLeasing(store, victim, source);
        MutableClock dispatcherClock = new MutableClock();
        FakeEngagementGateway gateway = new FakeEngagementGateway(Duration.ofMillis(5));
        AimdCapacityLimiter limiter = fixedLimiter(2, 2);

        try (LogCapture log = LogCapture.of(FanOutDispatcher.class);
                FanOutDispatcher dispatcher = dispatcher(leasing, store, new RoundRobinScheduler(), dispatcherClock,
                        gateway, limiter, config(Duration.ofSeconds(30), Duration.ZERO))) {
            dispatcher.start();
            await().atMost(TIMEOUT).until(() -> store.countUnfinished(healthy) == 0);
            await().atMost(TIMEOUT).until(() -> leasing.victimAsks() == 1);

            passesWithoutTheClockMoving(leasing, 20);
            assertThat(leasing.victimAsks()).as("cool-off of one idle delay (5 ms) has not passed").isEqualTo(1);
            dispatcherClock.advance(Duration.ofMillis(5));
            await().atMost(TIMEOUT).until(() -> leasing.victimAsks() >= 2);

            assertThat(log.at(Level.WARNING)).extracting(LogCapture.Entry::message)
                    .anySatisfy(message -> assertThat(message).contains(stolen.toString()).contains(victim.toString())
                            .contains("lease expires").contains("skipping it for"))
                    .allSatisfy(message -> assertThat(message).doesNotContain("WorkItem[").doesNotContain("Lease["));
        }

        assertThat(gateway.submittedAttemptIds(new EngagementId("c-source-e0"))).as("the foreign item was not run")
                .isEmpty();
        assertThat(store.statusOf(stolen)).as("left to lease expiry").isEqualTo(ItemStatus.LEASED);
        assertThat(store.count(ItemStatus.DONE)).as("the healthy firm was served in the same passes").isEqualTo(3);
        assertTheFullLimitIsAvailable(limiter);
    }

    private static void passesWithoutTheClockMoving(ForeignLeasing leasing, int passes) {
        int target = leasing.passes() + passes;
        await().atMost(TIMEOUT).until(() -> leasing.passes() >= target);
    }

    // ---- no port call can hold the worker for longer than the call timeout ----

    @Test
    void aStatusReadThatNeverReturnsReleasesItsPermitSoTheItemIsLeasedAgain() {
        publish(BIG_FIRM, 1);
        HangingGateway hanging = new HangingGateway(new FakeEngagementGateway(Duration.ZERO), Hang.STATUS, true);
        ItemId id = ItemId.of(BIG_FIRM, PIN, new EngagementId("big-e0"));

        try (FanOutDispatcher dispatcher = dispatcher(store, store, new RoundRobinScheduler(), clock, hanging,
                fixedLimiter(1, 1), config(Duration.ofSeconds(30), Duration.ZERO, Duration.ofMillis(100)))) {
            dispatcher.start();

            // Delivery 1 reads the status three times (each cut off after 100 ms) and requeues as
            // outcome unknown; delivery 2 needs the only permit again, so it was released.
            await().atMost(TIMEOUT).until(() -> hanging.statusCalls() >= 4);
        } finally {
            hanging.release();
        }

        assertThat(hanging.submittedKeys()).as("one submit, then only status reads").hasSize(1);
        assertThat(store.itemOf(id).attemptNumber()).as("the key never changed").isEqualTo(1);
        assertThat(store.statusOf(id)).isNotEqualTo(ItemStatus.FAILED);
    }

    @Test
    void aLeaseCallThatNeverReturnsForOneFirmDoesNotStopTheOtherFirmsFromBeingServedInLaterPasses() {
        FirmId stuck = new FirmId("a-stuck");
        FirmId healthy = new FirmId("b-ok");
        publish(stuck, 2);
        publish(healthy, 4);
        HangingLeasing leasing = new HangingLeasing(store, false, stuck);

        try (LogCapture log = LogCapture.of(FanOutDispatcher.class);
                FanOutDispatcher dispatcher = dispatcher(leasing, new FakeEngagementGateway(Duration.ofMillis(5)),
                        fixedLimiter(4, 4), config(Duration.ofSeconds(30), Duration.ZERO, Duration.ofMillis(100)))) {
            dispatcher.start();

            await().atMost(TIMEOUT).until(() -> store.countUnfinished(healthy) == 0);

            assertThat(leasing.hungCalls()).as("the stuck firm was tried").isGreaterThanOrEqualTo(1);
            assertThat(log.at(Level.WARNING)).anySatisfy(entry -> {
                assertThat(entry.message()).contains("Leasing failed for firm a-stuck").contains("skipping it for");
                assertThat(entry.thrown()).isInstanceOf(CallTimeoutException.class);
            });
        } finally {
            leasing.release();
        }

        assertThat(store.countUnfinished(stuck)).as("its items wait").isEqualTo(2);
        assertThat(store.count(ItemStatus.DONE)).isEqualTo(4);
    }

    @Test
    void closeReturnsWhileAStoreCallIsHungAndTheFailedPassIsLogged() {
        HangingLeasing leasing = new HangingLeasing(store, true, null);
        FanOutDispatcher dispatcher = dispatcher(leasing, new FakeEngagementGateway(Duration.ZERO), fixedLimiter(1, 1),
                config(Duration.ofSeconds(30), Duration.ZERO, Duration.ofMillis(100)));

        try (LogCapture log = LogCapture.of(FanOutDispatcher.class)) {
            dispatcher.start();
            await().atMost(TIMEOUT).until(() -> leasing.hungCalls() >= 1);

            assertTimeoutPreemptively(Duration.ofSeconds(10), dispatcher::close);

            assertThat(dispatcher.isRunning()).isFalse();
            assertThat(log.at(Level.WARNING)).anySatisfy(entry -> {
                assertThat(entry.message()).startsWith("Dispatch pass failed");
                assertThat(entry.thrown()).isInstanceOf(CallTimeoutException.class);
            });
        } finally {
            leasing.release();
        }
    }

    @Test
    void closeInterruptsADispatchThreadThatIsStuckOutsideAnyBoundedCallAndStillShutsTheWorkersDown() {
        CountDownLatch release = new CountDownLatch(1);
        FairScheduler stuck = new FairScheduler() {
            @Override
            public List<FirmId> order(Set<FirmId> candidates) {
                awaitUninterruptibly(release);
                return List.of();
            }

            @Override
            public void served(FirmId firmId) {
            }
        };
        FanOutDispatcher dispatcher = dispatcher(store, store, stuck, clock, new FakeEngagementGateway(Duration.ZERO),
                fixedLimiter(1, 1), config(Duration.ofSeconds(30), Duration.ZERO, Duration.ofMillis(100)));

        try (LogCapture log = LogCapture.of(FanOutDispatcher.class)) {
            dispatcher.start();

            assertTimeoutPreemptively(Duration.ofSeconds(10), dispatcher::close);

            assertThat(log.at(Level.WARNING)).extracting(LogCapture.Entry::message)
                    .anySatisfy(message -> assertThat(message).contains("dispatch thread did not stop")
                            .contains("interrupting it"));
        } finally {
            release.countDown();
        }
        await().atMost(TIMEOUT).until(() -> !dispatcher.isRunning());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        while (latch.getCount() > 0) {
            try {
                latch.await();
            } catch (InterruptedException ignored) {
                // a thread stuck in a native call would not notice either
            }
        }
    }

    // ---- a pass that keeps failing backs off and does not flood the log ----

    @Test
    void aPassThatKeepsFailingBacksOffWithDoublingDelaysAndLogsTheStackTraceOnlyForTheFirstOfARun() {
        // Calls 1-3 throw, call 4 succeeds, call 5 throws; later calls succeed.
        boolean[] failsOnCall = {true, true, true, false, true};
        AtomicInteger calls = new AtomicInteger();
        ItemLeasing scripted = new ItemLeasing() {
            @Override
            public Set<FirmId> firmsWithDispatchableItems(Instant now) {
                int call = calls.getAndIncrement();
                if (call < failsOnCall.length && failsOnCall[call]) {
                    throw new IllegalStateException("store down, call " + (call + 1));
                }
                return Set.of();
            }

            @Override
            public Optional<Lease> leaseNext(FirmId firmId, Instant now, Duration leaseDuration) {
                return Optional.empty();
            }
        };
        try (LogCapture log = LogCapture.of(FanOutDispatcher.class);
                FanOutDispatcher dispatcher = dispatcher(scripted, new FakeEngagementGateway(Duration.ZERO),
                        fixedLimiter(1, 1), config(Duration.ofSeconds(30), TIMEOUT))) {
            dispatcher.start();
            await().atMost(TIMEOUT).until(() -> passFailures(log).size() == 4);
            await().atMost(TIMEOUT).until(() -> calls.get() > failsOnCall.length);

            List<LogCapture.Entry> failures = passFailures(log);
            assertThat(failures).hasSize(4);
            assertThat(failures).extracting(entry -> entry.thrown() != null)
                    .as("a stack trace only on the first failure of a run").containsExactly(true, false, false, true);
            assertThat(failures.get(0).message()).contains("PT0.005S");
            assertThat(failures.get(1).message()).contains("PT0.01S").contains("store down, call 2");
            assertThat(failures.get(2).message()).contains("PT0.02S");
            assertThat(failures.get(3).message()).as("a pass that did not throw resets the delay")
                    .contains("PT0.005S");
        }
    }

    private static List<LogCapture.Entry> passFailures(LogCapture log) {
        return log.at(Level.WARNING).stream()
                .filter(entry -> entry.message().startsWith("Dispatch pass failed")).toList();
    }

    @Test
    void closeIsNotHeldUpByTheBackoffAfterAFailedPass() {
        AtomicInteger calls = new AtomicInteger();
        ItemLeasing alwaysDown = new ItemLeasing() {
            @Override
            public Set<FirmId> firmsWithDispatchableItems(Instant now) {
                calls.incrementAndGet();
                throw new IllegalStateException("store down");
            }

            @Override
            public Optional<Lease> leaseNext(FirmId firmId, Instant now, Duration leaseDuration) {
                return Optional.empty();
            }
        };
        DispatcherConfig slowRetry = new DispatcherConfig(
                Duration.ofSeconds(30), Duration.ofMillis(2), Duration.ofSeconds(5), Duration.ofSeconds(40),
                Duration.ZERO, Duration.ofSeconds(10));
        FanOutDispatcher dispatcher =
                dispatcher(alwaysDown, new FakeEngagementGateway(Duration.ZERO), fixedLimiter(1, 1), slowRetry);
        dispatcher.start();
        await().atMost(TIMEOUT).until(() -> calls.get() >= 1);

        assertTimeoutPreemptively(Duration.ofSeconds(10), dispatcher::close);
    }

    private static final class Fatal extends Error {

        private static final long serialVersionUID = 1L;

        private Fatal() {
            super("simulated VM error");
        }
    }

    /**
     * A store whose calls block until released, ignoring interrupts like a stuck socket read: every
     * {@code firmsWithDispatchableItems} call when {@code hangsFirms}, and every {@code leaseNext} for
     * {@code hungFirm}. A released call throws, so it has no late effect.
     */
    private static final class HangingLeasing implements ItemLeasing {

        private final ItemLeasing delegate;
        private final boolean hangsFirms;
        private final FirmId hungFirm;
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicInteger hungCalls = new AtomicInteger();

        private HangingLeasing(ItemLeasing delegate, boolean hangsFirms, FirmId hungFirm) {
            this.delegate = delegate;
            this.hangsFirms = hangsFirms;
            this.hungFirm = hungFirm;
        }

        @Override
        public Set<FirmId> firmsWithDispatchableItems(Instant now) {
            if (hangsFirms) {
                hang();
            }
            return delegate.firmsWithDispatchableItems(now);
        }

        @Override
        public Optional<Lease> leaseNext(FirmId firmId, Instant now, Duration leaseDuration) {
            if (firmId.equals(hungFirm)) {
                hang();
            }
            return delegate.leaseNext(firmId, now, leaseDuration);
        }

        private void hang() {
            hungCalls.incrementAndGet();
            awaitUninterruptibly(released);
            throw new IllegalStateException("released after the deadline");
        }

        int hungCalls() {
            return hungCalls.get();
        }

        void release() {
            released.countDown();
        }
    }

    /** Records, on the dispatcher thread, the firm of every item that was actually leased. */
    private static final class RecordingLeasing implements ItemLeasing {

        private final ItemLeasing delegate;
        private final List<FirmId> leaseOrder = new CopyOnWriteArrayList<>();

        private RecordingLeasing(ItemLeasing delegate) {
            this.delegate = delegate;
        }

        @Override
        public Set<FirmId> firmsWithDispatchableItems(Instant now) {
            return delegate.firmsWithDispatchableItems(now);
        }

        @Override
        public Optional<Lease> leaseNext(FirmId firmId, Instant now, Duration leaseDuration) {
            Optional<Lease> lease = delegate.leaseNext(firmId, now, leaseDuration);
            lease.ifPresent(granted -> leaseOrder.add(firmId));
            return lease;
        }

        List<FirmId> leaseOrder() {
            return List.copyOf(leaseOrder);
        }
    }

    /** Holds the first lease call for one firm until the test lets it go; records every lease call. */
    private static final class GatedLeasing implements ItemLeasing {

        private final ItemLeasing delegate;
        private final FirmId gatedFirm;
        private final boolean grantAfterRelease;
        private final AtomicBoolean gated = new AtomicBoolean();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final List<FirmId> leaseCalls = new CopyOnWriteArrayList<>();

        private GatedLeasing(ItemLeasing delegate, FirmId gatedFirm, boolean grantAfterRelease) {
            this.delegate = delegate;
            this.gatedFirm = gatedFirm;
            this.grantAfterRelease = grantAfterRelease;
        }

        @Override
        public Set<FirmId> firmsWithDispatchableItems(Instant now) {
            return delegate.firmsWithDispatchableItems(now);
        }

        @Override
        public Optional<Lease> leaseNext(FirmId firmId, Instant now, Duration leaseDuration) {
            leaseCalls.add(firmId);
            if (firmId.equals(gatedFirm) && gated.compareAndSet(false, true)) {
                entered.countDown();
                await(released);
                if (!grantAfterRelease) {
                    return Optional.empty();
                }
            }
            return delegate.leaseNext(firmId, now, leaseDuration);
        }

        void awaitEntered() {
            await(entered);
        }

        void release() {
            released.countDown();
        }

        List<FirmId> leaseCalls() {
            return List.copyOf(leaseCalls);
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("gate never opened");
                }
            } catch (InterruptedException e) {
                throw new IllegalStateException("interrupted at the gate", e);
            }
        }
    }

    /** A store whose {@code requeue} takes a while, as a loaded database would. */
    private static final class SlowRequeue implements LeaseWriter {

        private final LeaseWriter delegate;
        private final Duration delay;

        private SlowRequeue(LeaseWriter delegate, Duration delay) {
            this.delegate = delegate;
            this.delay = delay;
        }

        @Override
        public boolean renew(Lease lease, Instant newExpiry) {
            return delegate.renew(lease, newExpiry);
        }

        @Override
        public boolean finish(Lease lease, ItemStatus terminalStatus, String detail) {
            return delegate.finish(lease, terminalStatus, detail);
        }

        @Override
        public boolean requeue(Lease lease, RequeueReason reason, Instant notBefore) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                throw new IllegalStateException("interrupted while handing back", e);
            }
            return delegate.requeue(lease, reason, notBefore);
        }
    }

    /** A store that answers a lease request for one firm with a lease on an item of another firm. */
    private static final class ForeignLeasing implements ItemLeasing {

        private final ItemLeasing delegate;
        private final FirmId victim;
        private final FirmId source;
        private final AtomicInteger victimAsks = new AtomicInteger();
        private final AtomicInteger passes = new AtomicInteger();

        private ForeignLeasing(ItemLeasing delegate, FirmId victim, FirmId source) {
            this.delegate = delegate;
            this.victim = victim;
            this.source = source;
        }

        @Override
        public Set<FirmId> firmsWithDispatchableItems(Instant now) {
            passes.incrementAndGet();
            return delegate.firmsWithDispatchableItems(now);
        }

        @Override
        public Optional<Lease> leaseNext(FirmId firmId, Instant now, Duration leaseDuration) {
            if (firmId.equals(victim)) {
                victimAsks.incrementAndGet();
                return delegate.leaseNext(source, now, leaseDuration);
            }
            return delegate.leaseNext(firmId, now, leaseDuration);
        }

        int victimAsks() {
            return victimAsks.get();
        }

        int passes() {
            return passes.get();
        }
    }

    /** Throws from {@code leaseNext} for the selected firms, at most {@code maxFailures} times. */
    private static final class FlakyLeasing implements ItemLeasing {

        private final ItemLeasing delegate;
        private final Predicate<FirmId> failsFor;
        private final int maxFailures;
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicInteger passes = new AtomicInteger();

        private FlakyLeasing(ItemLeasing delegate, Predicate<FirmId> failsFor, int maxFailures) {
            this.delegate = delegate;
            this.failsFor = failsFor;
            this.maxFailures = maxFailures;
        }

        @Override
        public Set<FirmId> firmsWithDispatchableItems(Instant now) {
            passes.incrementAndGet();
            return delegate.firmsWithDispatchableItems(now);
        }

        @Override
        public Optional<Lease> leaseNext(FirmId firmId, Instant now, Duration leaseDuration) {
            attempts.incrementAndGet();
            if (failsFor.test(firmId) && failures.getAndIncrement() < maxFailures) {
                throw new IllegalStateException("store unavailable for " + firmId);
            }
            return delegate.leaseNext(firmId, now, leaseDuration);
        }

        int attempts() {
            return attempts.get();
        }

        /** How many dispatch passes have asked which firms have work. */
        int passes() {
            return passes.get();
        }

        int failures() {
            return Math.min(failures.get(), maxFailures);
        }
    }
}
