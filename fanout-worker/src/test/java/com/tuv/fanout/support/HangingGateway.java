package com.tuv.fanout.support;

import com.tuv.fanout.domain.AttemptId;
import com.tuv.fanout.port.ApplyCommand;
import com.tuv.fanout.port.AttemptStatus;
import com.tuv.fanout.port.EngagementGateway;
import com.tuv.fanout.port.SubmitResult;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A gateway whose submit or status call blocks until the test releases it, as a downstream
 * system that accepted the connection and then went quiet. Gated with latches, never sleeps.
 * A released call throws, so it never has a late effect on the delegate. The other call goes
 * straight to the delegate.
 */
public final class HangingGateway implements EngagementGateway {

    public enum Hang {
        SUBMIT, STATUS
    }

    private final EngagementGateway delegate;
    private final Hang hang;
    private final boolean ignoresInterrupts;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private final CountDownLatch interrupted = new CountDownLatch(1);
    private final List<AttemptId> submittedKeys = new CopyOnWriteArrayList<>();
    private final AtomicInteger statusCalls = new AtomicInteger();

    /** @param ignoresInterrupts whether the blocked call keeps waiting when interrupted, like a stuck socket read */
    public HangingGateway(EngagementGateway delegate, Hang hang, boolean ignoresInterrupts) {
        this.delegate = delegate;
        this.hang = hang;
        this.ignoresInterrupts = ignoresInterrupts;
    }

    @Override
    public SubmitResult submit(ApplyCommand command) {
        submittedKeys.add(command.attemptId());
        if (hang == Hang.SUBMIT) {
            block();
        }
        return delegate.submit(command);
    }

    @Override
    public AttemptStatus status(AttemptId attemptId) {
        statusCalls.incrementAndGet();
        if (hang == Hang.STATUS) {
            block();
        }
        return delegate.status(attemptId);
    }

    private void block() {
        entered.countDown();
        boolean wasInterrupted = false;
        try {
            while (true) {
                try {
                    released.await();
                    break;
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    if (!ignoresInterrupts) {
                        throw new IllegalStateException("interrupted while blocked", e);
                    }
                    wasInterrupted = true;
                }
            }
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
        throw new IllegalStateException("released after the deadline");
    }

    /** Lets every blocked call, present and future, go. */
    public void release() {
        released.countDown();
    }

    public boolean awaitEntered() throws InterruptedException {
        return entered.await(20, TimeUnit.SECONDS);
    }

    public boolean awaitInterrupted() throws InterruptedException {
        return interrupted.await(20, TimeUnit.SECONDS);
    }

    public List<AttemptId> submittedKeys() {
        return List.copyOf(submittedKeys);
    }

    public int statusCalls() {
        return statusCalls.get();
    }
}
