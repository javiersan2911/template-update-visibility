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
import java.util.Objects;
import java.util.Optional;
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
 * <p>The dispatcher keeps no state of its own that matters. Killing it at any point loses
 * nothing: leases expire and another instance continues with the same idempotency keys.
 */
public final class FanOutDispatcher implements AutoCloseable {

    private static final Logger LOG = System.getLogger(FanOutDispatcher.class.getName());

    private final ItemLeasing store;
    private final FairScheduler scheduler;
    private final CapacityLimiter limiter;
    private final ItemProcessor processor;
    private final DispatcherConfig config;
    private final Clock clock;

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
     * another worker can resume it at once under the same idempotency key.
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
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
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
     * Offers every firm with work at most one slot, in fair order.
     *
     * @return whether anything was started; if not, nothing will change until an item
     *         finishes or new work arrives
     */
    private boolean dispatchPass() {
        boolean started = false;
        for (FirmId firm : scheduler.order(store.firmsWithDispatchableItems(clock.instant()))) {
            if (!limiter.hasCapacity()) {
                break;
            }
            started |= tryStart(firm);
        }
        return started;
    }

    private boolean tryStart(FirmId firm) {
        Optional<Permit> acquired = limiter.tryAcquire(firm);
        if (acquired.isEmpty()) {
            return false;
        }
        Permit permit = acquired.get();
        try {
            Optional<Lease> lease = store.leaseNext(firm, clock.instant(), config.leaseDuration());
            if (lease.isEmpty()) {
                permit.close();
                return false;
            }
            scheduler.served(firm);
            workers.execute(() -> runItem(lease.get(), permit));
            return true;
        } catch (RuntimeException e) {
            permit.close();
            throw e;
        }
    }

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
