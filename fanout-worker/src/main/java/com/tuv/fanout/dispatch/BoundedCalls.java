package com.tuv.fanout.dispatch;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Runs a port call under a deadline the adapter cannot forget. The call runs on its own
 * virtual thread and the caller waits for it for at most the timeout; on expiry the call is
 * interrupted and {@link CallTimeoutException} is thrown. A call is cheap to run this way,
 * since virtual threads cost almost nothing while parked.
 *
 * <p>What the caller sees is what it would have seen from a direct call:
 * <ul>
 *   <li>A {@link RuntimeException} or {@link Error} thrown by the call propagates as itself.</li>
 *   <li>If the call leaves its own thread interrupted, the caller's thread is interrupted
 *       too. That is how an adapter that caught an {@code InterruptedException} reports a
 *       shutdown, so the caller's interrupt-aware code still notices it.</li>
 *   <li>If the waiting thread is interrupted, the call is cancelled, the interrupt flag is
 *       restored and a {@link CancellationException} is thrown.</li>
 *   <li>A flag already set when the call starts is carried across it: the call is not
 *       abandoned, and the flag is set again afterwards. The interrupt then still reaches
 *       the next blocking operation, and a hand-back write made while shutting down is not
 *       refused by a store client that fails fast on an interrupted thread.</li>
 * </ul>
 *
 * <p>Known residuals: a call that ignores interruption keeps its virtual thread until it
 * returns, and its effect, if any, still happens late. The deadline includes scheduling delay,
 * not only the call's own time. An adapter that blocks while pinning its carrier thread (inside
 * {@code synchronized} on JDK 21, or in native I/O) can starve other virtual threads, the
 * waiting ones included. Abandoned calls are not counted against any permit, so many of them
 * can pile up behind a downstream system that has stopped answering.
 *
 * <p>A call runs on a thread of its own, so a thread-local the adapter relies on (a
 * transaction, a logging context) is not visible to it.
 */
final class BoundedCalls {

    private final Duration timeout;
    private final long timeoutNanos;

    BoundedCalls(Duration timeout) {
        this.timeout = timeout;
        this.timeoutNanos = nanosSaturating(timeout);
    }

    /** @param what names the call in the timeout message, for example {@code "gateway.status"} */
    <T> T call(String what, Supplier<T> action) {
        boolean carriedInterrupt = Thread.interrupted();
        try {
            return callAndWait(what, action);
        } finally {
            if (carriedInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private <T> T callAndWait(String what, Supplier<T> action) {
        AtomicBoolean leftInterrupted = new AtomicBoolean();
        FutureTask<T> task = new FutureTask<>(() -> {
            try {
                return action.get();
            } finally {
                if (Thread.currentThread().isInterrupted()) {
                    leftInterrupted.set(true);
                }
            }
        });
        Thread.ofVirtual().name("fanout-call").start(task);
        try {
            return collect(task.get(timeoutNanos, TimeUnit.NANOSECONDS), leftInterrupted);
        } catch (TimeoutException e) {
            return settleAfterDeadline(what, timeout, task, leftInterrupted);
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new CancellationException(what + " was cancelled: the waiting thread was interrupted");
        } catch (ExecutionException e) {
            passOnInterrupt(leftInterrupted);
            throw unwrap(what, e.getCause());
        }
    }

    private static <T> T collect(T result, AtomicBoolean leftInterrupted) {
        passOnInterrupt(leftInterrupted);
        return result;
    }

    /**
     * The wait timed out. Cancelling fails only if the call finished in the meantime; its result,
     * or its own exception, is then used as if the wait had not timed out, rather than discarded
     * as a timeout. Package-private so that moment can be tested without racing.
     */
    static <T> T settleAfterDeadline(
            String what, Duration timeout, FutureTask<T> task, AtomicBoolean leftInterrupted) {
        if (task.cancel(true)) {
            throw new CallTimeoutException(what + " did not return within " + timeout);
        }
        try {
            return collect(task.get(), leftInterrupted);
        } catch (ExecutionException e) {
            passOnInterrupt(leftInterrupted);
            throw unwrap(what, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException(what + " was cancelled: the waiting thread was interrupted");
        }
    }

    private static void passOnInterrupt(AtomicBoolean leftInterrupted) {
        if (leftInterrupted.get()) {
            Thread.currentThread().interrupt();
        }
    }

    private static RuntimeException unwrap(String what, Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(what + " threw a checked exception", cause);
    }

    private static long nanosSaturating(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }
}
