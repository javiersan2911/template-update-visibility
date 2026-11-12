package com.tuv.fanout.support;

import com.tuv.fanout.domain.AttemptId;
import com.tuv.fanout.domain.EngagementId;
import com.tuv.fanout.domain.FirmId;
import com.tuv.fanout.port.ApplyCommand;
import com.tuv.fanout.port.AttemptStatus;
import com.tuv.fanout.port.EngagementGateway;
import com.tuv.fanout.port.SubmitResult;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stand-in for the engagement system. It honours the gateway contract (deduplicates on the
 * attempt id, takes {@code latency} to finish an attempt) and records what it was asked to
 * do, so tests can assert on effects and on the concurrency it was subjected to.
 */
public final class FakeEngagementGateway implements EngagementGateway {

    public enum Behavior {
        SUCCEED, FAIL_RETRYABLE, FAIL_PERMANENT, THROTTLE, REJECT, HANG, THROW_ON_SUBMIT, LOSE_SUBMIT
    }

    private final Duration latency;
    private final Map<EngagementId, Deque<Behavior>> scripts = new HashMap<>();
    private final Map<AttemptId, Attempt> attempts = new LinkedHashMap<>();
    private final List<ApplyCommand> submissions = new ArrayList<>();
    private final Map<FirmId, Integer> maxConcurrentByFirm = new HashMap<>();
    private int maxConcurrent;

    public FakeEngagementGateway(Duration latency) {
        this.latency = latency;
    }

    /** Behaviours for successive new attempts on the engagement; afterwards it succeeds. */
    public synchronized void script(EngagementId engagementId, Behavior... behaviors) {
        scripts.put(engagementId, new ArrayDeque<>(List.of(behaviors)));
    }

    @Override
    public synchronized SubmitResult submit(ApplyCommand command) {
        submissions.add(command);
        if (attempts.containsKey(command.attemptId())) {
            return new SubmitResult.Accepted();
        }
        Behavior behavior = nextBehavior(command.engagementId());
        switch (behavior) {
            case THROTTLE:
                return new SubmitResult.Throttled(Duration.ofMillis(1));
            case REJECT:
                return new SubmitResult.Rejected("version mismatch");
            case THROW_ON_SUBMIT:
                throw new IllegalStateException("connection reset");
            case LOSE_SUBMIT:
                return new SubmitResult.Accepted();
            default:
                attempts.put(command.attemptId(), new Attempt(command, behavior, System.nanoTime() + latency.toNanos()));
                recordConcurrency(command.firmId());
                return new SubmitResult.Accepted();
        }
    }

    @Override
    public synchronized AttemptStatus status(AttemptId attemptId) {
        Attempt attempt = attempts.get(attemptId);
        if (attempt == null) {
            return AttemptStatus.NOT_FOUND;
        }
        if (attempt.isRunning()) {
            return AttemptStatus.IN_PROGRESS;
        }
        return switch (attempt.behavior) {
            case FAIL_RETRYABLE -> AttemptStatus.FAILED_RETRYABLE;
            case FAIL_PERMANENT -> AttemptStatus.FAILED_PERMANENT;
            default -> AttemptStatus.SUCCEEDED;
        };
    }

    /** How many times the engagement was really processed to success, deduplication applied. */
    public synchronized long successfulApplies(EngagementId engagementId) {
        return attempts.values().stream()
                .filter(attempt -> attempt.command.engagementId().equals(engagementId))
                .filter(attempt -> attempt.behavior == Behavior.SUCCEED)
                .count();
    }

    public synchronized List<AttemptId> submittedAttemptIds(EngagementId engagementId) {
        return submissions.stream()
                .filter(command -> command.engagementId().equals(engagementId))
                .map(ApplyCommand::attemptId)
                .toList();
    }

    public synchronized int submissionCount() {
        return submissions.size();
    }

    public synchronized int maxConcurrent() {
        return maxConcurrent;
    }

    public synchronized int maxConcurrentFor(FirmId firmId) {
        return maxConcurrentByFirm.getOrDefault(firmId, 0);
    }

    private Behavior nextBehavior(EngagementId engagementId) {
        Deque<Behavior> script = scripts.get(engagementId);
        return script == null || script.isEmpty() ? Behavior.SUCCEED : script.poll();
    }

    private void recordConcurrency(FirmId firmId) {
        int running = 0;
        int runningForFirm = 0;
        for (Attempt attempt : attempts.values()) {
            if (attempt.isRunning()) {
                running++;
                if (attempt.command.firmId().equals(firmId)) {
                    runningForFirm++;
                }
            }
        }
        maxConcurrent = Math.max(maxConcurrent, running);
        maxConcurrentByFirm.merge(firmId, runningForFirm, Math::max);
    }

    private record Attempt(ApplyCommand command, Behavior behavior, long completesAtNanos) {

        private boolean isRunning() {
            return behavior == Behavior.HANG || System.nanoTime() < completesAtNanos;
        }
    }
}
