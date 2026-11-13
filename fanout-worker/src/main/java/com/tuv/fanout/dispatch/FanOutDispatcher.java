package com.tuv.fanout.dispatch;

import com.tuv.fanout.capacity.CapacityLimiter;
import com.tuv.fanout.capacity.Permit;
import com.tuv.fanout.domain.FirmId;
import com.tuv.fanout.domain.Lease;
import com.tuv.fanout.port.ItemLeasing;
import com.tuv.fanout.schedule.FairScheduler;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The fan-out worker's control loop. One thread admits work; each admitted item runs on
 * its own virtual thread, which spends nearly all of its minute-long life parked.
 *
 * <p>An item is started only when all of these hold: its firm is next in the fair order,
 * the limiter grants a permit, and the store grants a lease. The permit bounds load on the
 * downstream system; the lease makes the item exclusive across workers and processes.
 *
 * <p>A firm whose lease call throws is logged, skipped for a cool-off that doubles with
 * each consecutive failure, and passed over in the rotation, so one broken firm cannot
 * stall the rest.
 *
 * <p>The dispatcher keeps no state of its own that matters. Killing it at any point loses
 * nothing: leases expire and another instance continues with the same idempotency keys.
 *
 * <p><strong>Capacity bounds need a single active dispatcher.</strong> The limiter is in
 * memory, so its global limit and per-firm caps hold per process. The architecture runs the
 * dispatcher behind leader election for that reason. Several workers on one store are safe
 * for exactly-once processing, because leases live in the store, but each has its own
 * limiter and so the limits multiply by the number of workers.
 */
public final class FanOutDispatcher implements AutoCloseable {

    private static final Logger LOG = System.getLogger(FanOutDispatcher.class.getName());

    /** Longest a firm whose lease calls keep failing is left alone. */
    private static final Duration MAX_COOL_OFF = Duration.ofMinutes(1);

    /** How long {@code close()} waits, after interrupting workers, for their hand-backs. */
    private static final Duration HAND_BACK_WAIT = Duration.ofSeconds(10);

    private final ItemLeasing store;
    private final FairScheduler scheduler;
    private final CapacityLimiter limiter;
    private final ItemProcessor processor;
    private final DispatcherConfig config;
    private final Clock clock;

    /** Touched by the dispatcher thread only. */
    private final Map<FirmId, CoolOff> coolOffs = new HashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore wakeUps = new Semaphore(0);
    private volatile boolean running;
    private boolean closed;
    private Thread dispatchThread;

    public FanOutDispatcher(
            ItemLeasing store,
            FairScheduler scheduler,
            CapacityLimiter limiter,
            ItemProcessor processor,
            DispatcherConfig config,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized void start() {
        if (dispatchThread != null || closed) {
            throw new IllegalStateException("a dispatcher can be started only once");
        }
        running = true;
        dispatchThread = Thread.ofPlatform().name("fanout-dispatcher").start(this::dispatchLoop);
    }

    /**
     * Stops admitting work, then waits up to the shutdown grace for in-flight items. Items
     * still running after that are interrupted; each hands itself back to the queue so
     * another worker can resume it at once under the same idempotency key. {@code close()}
     * then waits a further fixed 10 seconds for those hand-backs to complete, and logs a
     * warning if some did not; their items are then left to lease expiry.
     */
    @Override
    public synchronized void close() {
        closed = true;
        running = false;
        wakeUps.release();
        try {
            if (dispatchThread != null) {
                dispatchThread.join();
            }
            workers.shutdown();
            if (!workers.awaitTermination(config.shutdownGrace().toMillis(), TimeUnit.MILLISECONDS)) {
                workers.shutdownNow();
                awaitHandBacks();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void awaitHandBacks() throws InterruptedException {
        if (!workers.awaitTermination(HAND_BACK_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
            LOG.log(Level.WARNING, "Interrupted workers did not finish handing back their items within "
                    + HAND_BACK_WAIT + "; those items are left to lease expiry");
        }
    }

    private void dispatchLoop() {
        while (running) {
            try {
                if (!dispatchPass()) {
                    waitForWork();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Dispatch pass failed; retrying after the idle delay", e);
                sleepQuietly();
            }
        }
    }

    /**
     * Offers every firm with work at most one slot, in fair order. A firm whose lease call
     * fails is skipped for a growing cool-off and never holds up the others.
     *
     * @return whether anything was started; if not, nothing will change until an item
     *         finishes or new work arrives
     */
    private boolean dispatchPass() {
        Instant now = clock.instant();
        Set<FirmId> firms = store.firmsWithDispatchableItems(now);
        coolOffs.keySet().retainAll(firms);
        boolean started = false;
        for (FirmId firm : scheduler.order(firms)) {
            if (!limiter.hasCapacity()) {
                break;
            }
            if (!isCoolingOff(firm, now)) {
                started |= tryStart(firm);
            }
        }
        return started;
    }

    private boolean tryStart(FirmId firm) {
        Optional<Permit> acquired = limiter.tryAcquire(firm);
        if (acquired.isEmpty()) {
            return false;
        }
        Permit permit = acquired.get();
        Optional<Lease> lease;
        try {
            lease = store.leaseNext(firm, clock.instant(), config.leaseDuration());
        } catch (RuntimeException e) {
            permit.close();
            coolOff(firm, e);
            scheduler.served(firm);
            return false;
        }
        coolOffs.remove(firm);
        if (lease.isEmpty()) {
            permit.close();
            return false;
        }
        try {
            scheduler.served(firm);
            workers.execute(() -> runItem(lease.get(), permit));
            return true;
        } catch (RuntimeException e) {
            permit.close();
            throw e;
        }
    }

    private boolean isCoolingOff(FirmId firm, Instant now) {
        CoolOff coolOff = coolOffs.get(firm);
        return coolOff != null && now.isBefore(coolOff.notBefore());
    }

    /** Skips the firm until a not-before instant; the delay doubles with each consecutive failure. */
    private void coolOff(FirmId firm, RuntimeException cause) {
        int failures = coolOffs.containsKey(firm) ? coolOffs.get(firm).failures() + 1 : 1;
        Duration delay = config.idleDelay().multipliedBy(1L << Math.min(failures - 1, 20));
        if (delay.compareTo(MAX_COOL_OFF) > 0) {
            delay = MAX_COOL_OFF;
        }
        coolOffs.put(firm, new CoolOff(failures, clock.instant().plus(delay)));
        LOG.log(Level.WARNING, "Leasing failed for firm " + firm + " (" + failures
                + " consecutive); skipping it for " + delay, cause);
    }

    private record CoolOff(int failures, Instant notBefore) {}

    private void runItem(Lease lease, Permit permit) {
        try (permit) {
            processor.process(lease);
        } finally {
            wakeUps.release();
        }
    }

    private void waitForWork() throws InterruptedException {
        wakeUps.tryAcquire(config.idleDelay().toMillis(), TimeUnit.MILLISECONDS);
        wakeUps.drainPermits();
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(config.idleDelay());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
