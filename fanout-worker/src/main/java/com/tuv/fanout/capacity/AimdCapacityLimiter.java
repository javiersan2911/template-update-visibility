package com.tuv.fanout.capacity;

import com.tuv.fanout.domain.FirmId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Global concurrency limit adapted by additive increase / multiplicative decrease, plus a
 * fixed cap per firm so one large firm cannot occupy the whole pool.
 *
 * <p>When the downstream system names a wait (HTTP 429 with Retry-After), all admissions
 * stop until it has passed, so one refusal is not followed by a stream of further probes.
 *
 * <p>Lowering the limit never revokes permits already handed out; in-flight work drains
 * and new admissions wait until usage is back under the limit.
 */
public final class AimdCapacityLimiter implements CapacityLimiter {

    private final AimdConfig config;
    private final Clock clock;
    private final Map<FirmId, Integer> inFlightByFirm = new HashMap<>();

    private int limit;
    private int inFlight;
    private Instant lastIncrease;
    private Instant lastDecrease;
    private Instant pausedUntil;

    public AimdCapacityLimiter(AimdConfig config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.limit = config.initialLimit();
        this.lastIncrease = clock.instant();
        this.pausedUntil = lastIncrease;
    }

    @Override
    public synchronized boolean hasCapacity() {
        return inFlight < limit && !isPaused();
    }

    @Override
    public synchronized Optional<Permit> tryAcquire(FirmId firmId) {
        int firmInFlight = inFlightByFirm.getOrDefault(firmId, 0);
        if (inFlight >= limit || firmInFlight >= config.perFirmCap() || isPaused()) {
            return Optional.empty();
        }
        inFlight++;
        inFlightByFirm.put(firmId, firmInFlight + 1);
        return Optional.of(new FirmPermit(firmId));
    }

    @Override
    public synchronized void recordSuccess() {
        Instant now = clock.instant();
        if (now.isBefore(lastIncrease.plus(config.increaseInterval()))) {
            return;
        }
        limit = Math.min(config.ceiling(), limit + config.increaseStep());
        lastIncrease = now;
    }

    @Override
    public synchronized void recordOverload(Duration pause) {
        Instant now = clock.instant();
        Instant resumeAt = now.plus(pause);
        if (resumeAt.isAfter(pausedUntil)) {
            pausedUntil = resumeAt;
        }
        if (lastDecrease != null && now.isBefore(lastDecrease.plus(config.decreaseCooldown()))) {
            return;
        }
        limit = Math.max(config.floor(), (int) (limit * config.decreaseFactor()));
        lastDecrease = now;
        lastIncrease = now;
    }

    public synchronized int currentLimit() {
        return limit;
    }

    private boolean isPaused() {
        return clock.instant().isBefore(pausedUntil);
    }

    private synchronized void release(FirmId firmId) {
        inFlight--;
        inFlightByFirm.computeIfPresent(firmId, (firm, count) -> count == 1 ? null : count - 1);
    }

    private final class FirmPermit implements Permit {

        private final FirmId firmId;
        private final AtomicBoolean released = new AtomicBoolean();

        private FirmPermit(FirmId firmId) {
            this.firmId = firmId;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                release(firmId);
            }
        }
    }
}
