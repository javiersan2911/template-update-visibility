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
import java.util.concurrent.RejectedExecutionException;
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
 * <p>Every store call the dispatcher makes runs under the configured call timeout, so a hung
 * store cannot block the admission thread, and so {@link #close()} cannot wait for ever. A
 * lease call that times out is treated like one that threw; a timed-out
 * {@code firmsWithDispatchableItems} fails the pass, which is retried after a backoff.
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

    /** Added to the call timeout to bound how long {@code close()} waits for the dispatch thread. */
    static final Duration DISPATCH_JOIN_MARGIN = Duration.ofSeconds(1);

    private final ItemLeasing store;
    private final FairScheduler scheduler;
    private final CapacityLimiter limiter;
    private final ItemProcessor processor;
    private final DispatcherConfig config;
    private final Clock clock;
    private final BoundedCalls calls;

    /** Touched by the dispatcher thread only. */
    private final Map<FirmId, CoolOff> coolOffs = new HashMap<>();
    /** Passes in a row that threw; touched by the dispatcher thread only. */
    private int consecutivePassFailures;
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
        this.calls = new BoundedCalls(config.callTimeout());
    }

    public synchronized void start() {
        if (dispatchThread != null || closed) {
            throw new IllegalStateException("a dispatcher can be started only once");
        }
        running = true;
        dispatchThread = Thread.ofPlatform().name("fanout-dispatcher").start(this::dispatchLoop);
    }

    /**
     * Whether the dispatcher is admitting work: true from {@link #start()} until
     * {@link #close()} is called or the dispatch loop ends, whether on an interrupt or on an
     * {@link Error}. A supervisor can poll this to notice a dispatcher that died.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Stops admitting work, then waits up to the shutdown grace for in-flight items. Items
     * still running after that are interrupted; each hands itself back to the queue so
     * another worker can resume it at once under the same idempotency key. {@code close()}
     * then waits a further fixed 10 seconds for those hand-backs to complete, and logs a
     * warning if some did not; their items are then left to lease expiry.
     *
     * <p>The wait for the dispatch thread is bounded by the call timeout plus a small margin,
     * because every call that thread makes is bounded. If the thread is still alive after
     * that, it is interrupted, a warning is logged, and the workers are shut down anyway.
     */
    @Override
    public synchronized void close() {
        closed = true;
        running = false;
        wakeUps.release();
        try {
            if (dispatchThread != null) {
                joinDispatchThread();
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

    /**
     * Every call the dispatch thread makes is bounded by the call timeout, so a pass ends
     * within about that long once {@code running} is false. A thread still alive after that
     * is stuck somewhere else (for example in the scheduler): it is interrupted and left, and
     * the workers are shut down regardless.
     */
    private void joinDispatchThread() throws InterruptedException {
        Duration patience = config.callTimeout();
        try {
            patience = patience.plus(DISPATCH_JOIN_MARGIN);
        } catch (ArithmeticException tooLong) {
            // keep the configured timeout itself
        }
        if (!dispatchThread.join(patience)) {
            LOG.log(Level.WARNING, "The dispatch thread did not stop within " + patience
                    + "; interrupting it and shutting the workers down regardless");
            dispatchThread.interrupt();
        }
    }

    private void awaitHandBacks() throws InterruptedException {
        if (!workers.awaitTermination(HAND_BACK_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
            LOG.log(Level.WARNING, "Interrupted workers did not finish handing back their items within "
                    + HAND_BACK_WAIT + "; those items are left to lease expiry");
        }
    }

    /**
     * Runs passes until stopped. A pass that throws is retried after a growing delay; an
     * {@link Error} ends the loop, is logged at ERROR with the reason, and propagates, so the
     * thread never dies silently while {@link #isRunning()} still says true.
     */
    private void dispatchLoop() {
        try {
            while (running) {
                runOnePass();
            }
        } catch (InterruptedException e) {
            LOG.log(Level.INFO, "Dispatcher thread was interrupted; it has stopped admitting work");
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "Dispatcher has stopped admitting work because of " + t
                    + "; items already leased return after their leases expire", t);
            throw t;
        } finally {
            running = false;
        }
    }

    private void runOnePass() throws InterruptedException {
        try {
            boolean started = dispatchPass();
            consecutivePassFailures = 0;
            if (!started) {
                waitForWork();
            }
        } catch (RuntimeException e) {
            backOffAfterFailedPass(e);
        }
    }

    /**
     * The delay doubles with each consecutive failed pass and resets after a pass that does
     * not throw. Only the first failure of a run logs its stack trace.
     */
    private void backOffAfterFailedPass(RuntimeException cause) throws InterruptedException {
        consecutivePassFailures++;
        Duration delay = backoffDelay(config.idleDelay(), consecutivePassFailures);
        if (consecutivePassFailures == 1) {
            LOG.log(Level.WARNING, "Dispatch pass failed; retrying in " + delay, cause);
        } else {
            LOG.log(Level.WARNING, "Dispatch pass failed again (" + consecutivePassFailures
                    + " consecutive): " + cause + "; retrying in " + delay);
        }
        sleepUnlessStopped(delay);
    }

    /**
     * Offers every firm with work at most one slot, in fair order. A firm whose lease call
     * fails is skipped for a growing cool-off and never holds up the others. The pass ends
     * early once {@link #close()} has been called, so it starts nothing more.
     *
     * @return whether anything was started; if not, nothing will change until an item
     *         finishes or new work arrives
     */
    private boolean dispatchPass() {
        Instant now = clock.instant();
        Set<FirmId> firms =
                calls.call("store.firmsWithDispatchableItems", () -> store.firmsWithDispatchableItems(now));
        coolOffs.keySet().retainAll(firms);
        boolean started = false;
        for (FirmId firm : scheduler.order(firms)) {
            if (!running || !limiter.hasCapacity()) {
                break;
            }
            if (!isCoolingOff(firm, now)) {
                // firms.size() includes firms cooling off, so a firm's share does not swell while one is away
                started |= tryStart(firm, firms.size());
            }
        }
        return started;
    }

    private boolean tryStart(FirmId firm, int contendingFirms) {
        Optional<Permit> acquired = limiter.tryAcquire(firm, contendingFirms);
        if (acquired.isEmpty()) {
            return false;
        }
        Permit permit = acquired.get();
        boolean handedToWorker = false;
        Lease granted = null;
        try {
            Optional<Lease> lease;
            try {
                Instant leaseAt = clock.instant();
                lease = calls.call("store.leaseNext", () -> store.leaseNext(firm, leaseAt, config.leaseDuration()));
            } catch (RuntimeException e) {
                coolOff(firm, "Leasing failed for firm " + firm, e);
                scheduler.served(firm);
                return false;
            }
            if (lease.isPresent() && !lease.get().item().firmId().equals(firm)) {
                coolOff(firm, foreignLeaseText(firm, lease.get()), null);
                scheduler.served(firm);
                return false;
            }
            coolOffs.remove(firm);
            if (lease.isEmpty()) {
                return false;
            }
            granted = lease.get();
            Lease item = granted;
            scheduler.served(firm);
            workers.execute(() -> runItem(item, permit));
            handedToWorker = true;
            return true;
        } catch (RuntimeException e) {
            if (granted == null) {
                throw e;
            }
            return notStarted(granted, e);
        } catch (Error e) {
            if (granted != null) {
                LOG.log(Level.WARNING, notStartedText(granted), e);
            }
            throw e;
        } finally {
            if (!handedToWorker) {
                permit.close(); // once runItem owns the permit, it closes it
            }
        }
    }

    /**
     * Names the item and both firms, not the record. Like {@link #notStartedText}, it says the item is
     * left to lease expiry, since the dispatcher holds only the leasing view of the store.
     */
    private static String foreignLeaseText(FirmId asked, Lease lease) {
        return "The store answered a lease request for firm " + asked + " with item " + lease.item().id()
                + " of firm " + lease.item().firmId() + "; it was not run and will be redelivered after its"
                + " lease expires";
    }

    private static String notStartedText(Lease lease) {
        return "Item " + lease.item().id() + " was leased but could not be started; it will be"
                + " redelivered after its lease expires";
    }

    /**
     * The item is leased but nothing runs it, and this class holds only the leasing view of
     * the store, so it cannot hand the item back: it is named here and comes back when its
     * lease expires. A refusal by the executor while closing is expected and not a failed
     * pass; anything else still fails the pass.
     */
    private boolean notStarted(Lease lease, RuntimeException cause) {
        if (cause instanceof RejectedExecutionException && !running) {
            LOG.log(Level.WARNING, notStartedText(lease) + " (the dispatcher is shutting down)");
            return false;
        }
        LOG.log(Level.WARNING, notStartedText(lease), cause);
        throw cause;
    }

    private boolean isCoolingOff(FirmId firm, Instant now) {
        CoolOff coolOff = coolOffs.get(firm);
        return coolOff != null && now.isBefore(coolOff.notBefore());
    }

    /**
     * Skips the firm until a not-before instant; the delay doubles with each consecutive failure.
     *
     * @param cause what was thrown, or null when the store answered but the answer was refused
     */
    private void coolOff(FirmId firm, String problem, RuntimeException cause) {
        int failures = coolOffs.containsKey(firm) ? coolOffs.get(firm).failures() + 1 : 1;
        Duration delay = backoffDelay(config.idleDelay(), failures);
        coolOffs.put(firm, new CoolOff(failures, clock.instant().plus(delay)));
        String text = problem + " (" + failures + " consecutive); skipping it for " + delay;
        if (cause == null) {
            LOG.log(Level.WARNING, text);
        } else {
            LOG.log(Level.WARNING, text, cause);
        }
    }

    /**
     * The wait after {@code failures} consecutive failures: {@code base}, doubled for each
     * failure after the first, never more than one minute, and never less than {@code base}
     * itself: a configured idle delay longer than the cap is the floor, not something to undercut.
     * Used for the failed-pass backoff and the per-firm cool-off alike.
     */
    static Duration backoffDelay(Duration base, int failures) {
        Duration cap = base.compareTo(MAX_COOL_OFF) > 0 ? base : MAX_COOL_OFF;
        try {
            Duration delay = base.multipliedBy(1L << Math.min(failures - 1, 20));
            return delay.compareTo(cap) > 0 ? cap : delay;
        } catch (ArithmeticException tooLong) {
            return cap;
        }
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

    /** Waits out the delay, but returns at once when {@link #close()} stops the dispatcher. */
    private void sleepUnlessStopped(Duration delay) throws InterruptedException {
        long deadline = System.nanoTime() + delay.toNanos();
        while (running) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            wakeUps.tryAcquire(remaining, TimeUnit.NANOSECONDS);
        }
    }
}
