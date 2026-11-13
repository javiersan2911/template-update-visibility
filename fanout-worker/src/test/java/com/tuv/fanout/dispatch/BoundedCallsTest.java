package com.tuv.fanout.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The one place the call deadline is enforced; the ports' own behaviour is tested where they are used. */
@Timeout(30)
class BoundedCallsTest {

    /** Only where a timeout is the expected result. */
    private static final Duration SHORT = Duration.ofMillis(100);
    /** Success paths: far above any scheduling delay on a loaded machine. */
    private static final Duration PATIENT = Duration.ofSeconds(5);

    private final BoundedCalls calls = new BoundedCalls(PATIENT);
    private final BoundedCalls shortCalls = new BoundedCalls(SHORT);

    @Test
    void returnsWhatTheCallReturns() {
        assertThat(calls.call("port", () -> "answer")).isEqualTo("answer");
        assertThat(calls.<String>call("port", () -> null)).isNull();
    }

    @Test
    void runsTheCallOnAnotherThread() {
        Thread caller = Thread.currentThread();

        assertThat(calls.call("port", Thread::currentThread)).isNotSameAs(caller);
    }

    @Test
    void aRuntimeExceptionFromTheCallPropagatesAsItself() {
        IllegalStateException thrown = new IllegalStateException("connection reset");

        assertThatThrownBy(() -> calls.call("port", () -> {
            throw thrown;
        })).isSameAs(thrown);
    }

    @Test
    void anErrorFromTheCallPropagatesAsItself() {
        AssertionError thrown = new AssertionError("simulated VM error");

        assertThatThrownBy(() -> calls.call("port", () -> {
            throw thrown;
        })).isSameAs(thrown);
    }

    @Test
    void aCheckedExceptionSneakedOutOfTheCallIsWrappedNotLost() {
        Exception checked = new Exception("sneaky");

        assertThatThrownBy(() -> calls.call("port", () -> BoundedCallsTest.<RuntimeException, String>sneak(checked)))
                .isInstanceOf(IllegalStateException.class).hasCause(checked);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T sneak(Throwable thrown) throws E {
        throw (E) thrown;
    }

    @Test
    void aCallThatOutlastsTheDeadlineThrowsCallTimeoutAndIsInterrupted() throws InterruptedException {
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean callerFlag = new AtomicBoolean(true);

        // assertTimeoutPreemptively runs the body on another thread, so the flag is read there.
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            assertThatThrownBy(() -> shortCalls.call("gateway.status", () -> {
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    interrupted.countDown();
                }
                return "late";
            })).isInstanceOf(CallTimeoutException.class).hasMessageContaining("gateway.status")
                    .hasMessageContaining(SHORT.toString());
            callerFlag.set(Thread.currentThread().isInterrupted());
        });

        assertThat(interrupted.await(5, TimeUnit.SECONDS)).as("the abandoned call was interrupted").isTrue();
        assertThat(callerFlag).as("the thread that made the call was not interrupted").isFalse();
    }

    @Test
    void aCallThatIgnoresInterruptionStillReleasesTheCaller() {
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThatThrownBy(() -> shortCalls.call("port", () -> {
                awaitUninterruptibly(release);
                return "late";
            })).isInstanceOf(CallTimeoutException.class));
        } finally {
            release.countDown();
        }
    }

    // ---- the deadline passes just as the call finishes ----
    // The race itself (get times out, then the call completes before cancel) cannot be forced
    // without a sleep or a seam inside FutureTask. What happens next is a method of its own, so
    // each outcome of that moment is tested directly and deterministically.

    @Test
    void ifTheCallFinishedInTheRaceItsResultIsReturnedNotATimeout() {
        FutureTask<String> task = new FutureTask<>(() -> "answer");
        task.run();

        assertThat(BoundedCalls.settleAfterDeadline("port", SHORT, task, new AtomicBoolean())).isEqualTo("answer");
    }

    @Test
    void ifTheCallFailedInTheRaceItsOwnExceptionIsRethrown() {
        IllegalStateException thrown = new IllegalStateException("connection reset");
        FutureTask<String> task = new FutureTask<>(() -> {
            throw thrown;
        });
        task.run();

        assertThatThrownBy(() -> BoundedCalls.settleAfterDeadline("port", SHORT, task, new AtomicBoolean()))
                .isSameAs(thrown);
    }

    @Test
    void ifTheCallFinishedInTheRaceWithItsThreadInterruptedTheSignalIsPassedOn() {
        FutureTask<String> task = new FutureTask<>(() -> "answer");
        task.run();
        try {
            assertThat(BoundedCalls.settleAfterDeadline("port", SHORT, task, new AtomicBoolean(true)))
                    .isEqualTo("answer");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void ifTheCallIsStillRunningAtTheDeadlineItIsCancelledAndATimeoutIsThrown() {
        FutureTask<String> task = new FutureTask<>(() -> "never run");

        assertThatThrownBy(() -> BoundedCalls.settleAfterDeadline("port", SHORT, task, new AtomicBoolean()))
                .isInstanceOf(CallTimeoutException.class);
        assertThat(task.isCancelled()).isTrue();
    }

    @Test
    void anInterruptWhileWaitingCancelsTheCallRestoresTheFlagAndThrows() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch callInterrupted = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean flagAfter = new AtomicBoolean();
        BoundedCalls patient = new BoundedCalls(Duration.ofSeconds(20));
        Thread waiter = Thread.ofPlatform().start(() -> {
            try {
                patient.call("port", () -> {
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException e) {
                        callInterrupted.countDown();
                    }
                    return "late";
                });
            } catch (RuntimeException e) {
                thrown.set(e);
            }
            flagAfter.set(Thread.currentThread().isInterrupted());
        });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        waiter.interrupt();
        waiter.join(5_000);

        assertThat(waiter.isAlive()).isFalse();
        assertThat(thrown.get()).isInstanceOf(CancellationException.class);
        assertThat(flagAfter).as("the shutdown signal is still there for the caller").isTrue();
        assertThat(callInterrupted.await(5, TimeUnit.SECONDS)).as("the call was cancelled").isTrue();
    }

    @Test
    void aFlagAlreadySetOnTheCallerDoesNotAbortTheCallAndIsStillSetAfterwards() {
        Thread.currentThread().interrupt();
        try {
            assertThat(calls.call("port", () -> "answer")).isEqualTo("answer");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aCallThatLeavesItsThreadInterruptedPassesTheSignalToTheCaller() {
        try {
            assertThat(calls.call("port", () -> {
                Thread.currentThread().interrupt();
                return "answer";
            })).isEqualTo("answer");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aCallThatThrowsWithItsThreadInterruptedPassesTheSignalToTheCaller() {
        IllegalStateException thrown = new IllegalStateException("client wrapped the interrupt");
        try {
            assertThatThrownBy(() -> calls.call("port", () -> {
                Thread.currentThread().interrupt();
                throw thrown;
            })).isSameAs(thrown);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aHugeTimeoutDoesNotOverflow() {
        assertThat(new BoundedCalls(Duration.ofSeconds(Long.MAX_VALUE)).call("port", () -> "answer"))
                .isEqualTo("answer");
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
