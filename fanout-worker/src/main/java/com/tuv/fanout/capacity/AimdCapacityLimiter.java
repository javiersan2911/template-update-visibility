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
 * cap per firm so one large firm cannot occupy the whole pool.
 *
 * <p>A firm may hold at most the smaller of its adaptive cap and its fair share of the
 * current global limit, {@code max(1, ceil(limit / contendingFirms))}, where the caller
 * says how many firms currently have work to dispatch. Without the share, a firm whose items
 * hang would take every slot the others free, because the scheduler only decides who is
 * offered the next slot and a slot held by a hung item is never freed.
 *
 * <p>The share is not work-conserving. A firm whose items are all leased, or that has no work,
 * leaves the contending count and the others then get the whole limit. But the count includes
 * every firm with dispatchable work, also one that is cooling off or whose adaptive cap is
 * penalised, so slots can sit idle while such a firm cannot use its share. And a share that
 * shrinks because a second firm appeared never revokes permits already held: a firm that was
 * alone can hold the whole pool until its items end, and the newcomer waits for them to drain.
 * The firm above its share simply gets no new slot until it is back under it.
 *
 * <p>Signals are scoped. A 429 ({@link #recordOverload}) is global: the limit is halved, at
 * most once per decrease cooldown. Trouble attributable to one firm's work
 * ({@link #recordFirmTrouble}) halves only that firm's cap, at most once per cooldown per
 * firm. The cap climbs back by the increase step for every full increase interval since
 * the firm's last decrease, by time alone: an idle firm, or one whose items end rejected or
 * skipped, recovers too. A firm back at the full cap is forgotten, so only penalised firms
 * are tracked. With a fixed configuration ({@link AimdConfig#adaptiveFirmCaps()} off) firm
 * trouble is ignored.
 * Trouble from two or more distinct firms inside one cooldown window points at the
 * downstream system rather than a tenant, and then the global limit is lowered as well.
 *
 * <p>When the downstream system names a wait (HTTP 429 with Retry-After), all admissions
 * stop until it has passed, so one refusal is not followed by a stream of further probes.
 * The pause is clamped to {@link AimdConfig#maxPause()}, so no downstream value can stop
 * admissions for longer than that, and none can overflow the clock arithmetic.
 *
 * <p>Lowering a limit never revokes permits already handed out; in-flight work drains
 * and new admissions wait until usage is back under the limit.
 *
 * <p><strong>Capacity bounds hold per process.</strong> All state here is in memory, so the
 * global limit and the per-firm caps bound one limiter instance only. They bound the
 * downstream load only if a single dispatcher is active, which the architecture ensures
 * with leader election. Several workers sharing one store remain safe for exactly-once
 * processing, because leases live in the store, but each brings its own limiter and the
 * limits multiply by the number of workers.
 */
public final class AimdCapacityLimiter implements CapacityLimiter {

    private final AimdConfig config;
    private final Clock clock;
    private final Map<FirmId, Integer> inFlightByFirm = new HashMap<>();
    /** Only firms whose stored cap is below {@code perFirmCap}; recovery is computed lazily from the clock. */
    private final Map<FirmId, FirmPenalty> penalties = new HashMap<>();
    /** When each firm last reported trouble; entries older than the decrease cooldown are dropped. */
    private final Map<FirmId, Instant> recentTrouble = new HashMap<>();

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
    public synchronized Optional<Permit> tryAcquire(FirmId firmId, int contendingFirms) {
        if (contendingFirms < 1) {
            throw new IllegalArgumentException("contending firms must be at least 1");
        }
        int firmInFlight = inFlightByFirm.getOrDefault(firmId, 0);
        int firmLimit = Math.min(capOf(firmId), fairShare(contendingFirms));
        if (inFlight >= limit || firmInFlight >= firmLimit || isPaused()) {
            return Optional.empty();
        }
        inFlight++;
        inFlightByFirm.put(firmId, firmInFlight + 1);
        return Optional.of(new FirmPermit(firmId));
    }

    @Override
    public synchronized void recordFirmTrouble(FirmId firmId) {
        Objects.requireNonNull(firmId, "firmId");
        if (!config.adaptiveFirmCaps()) {
            return;
        }
        Instant now = clock.instant();
        forgetRecoveredFirms(now);
        forgetOldTrouble(now);
        recentTrouble.put(firmId, now);
        decreaseFirmCap(firmId, now);
        if (recentTrouble.size() >= 2) {
            decreaseGlobally(now);
        }
    }

    @Override
    public synchronized void recordSuccess(FirmId firmId) {
        Objects.requireNonNull(firmId, "firmId");
        Instant now = clock.instant();
        forgetRecoveredFirms(now);
        forgetOldTrouble(now);
        increaseGlobally(now);
    }

    @Override
    public synchronized void recordOverload(Duration pause) {
        Instant now = clock.instant();
        forgetRecoveredFirms(now);
        Instant resumeAt = now.plus(clamp(pause));
        if (resumeAt.isAfter(pausedUntil)) {
            pausedUntil = resumeAt;
        }
        decreaseGlobally(now);
    }

    /** The firm's equal part of the current global limit, rounded up so the shares together cover it. */
    private int fairShare(int contendingFirms) {
        return Math.max(1, Math.ceilDiv(limit, contendingFirms));
    }

    /** The cap currently applied to the firm: its full cap unless it is being held back. */
    public synchronized int currentFirmCap(FirmId firmId) {
        return capOf(firmId);
    }

    /** Firms still held in the penalty map; recovered ones are dropped when feedback arrives or their cap is read. */
    public synchronized int penalisedFirmCount() {
        return penalties.size();
    }

    private void increaseGlobally(Instant now) {
        if (now.isBefore(lastIncrease.plus(config.increaseInterval()))) {
            return;
        }
        limit = saturatingAdd(limit, config.increaseStep(), config.ceiling());
        lastIncrease = now;
    }

    /** {@code value + step} capped at {@code ceiling}; computed in {@code long}, so a ceiling near {@code Integer.MAX_VALUE} cannot wrap. */
    private static int saturatingAdd(int value, int step, int ceiling) {
        return (int) Math.min(ceiling, (long) value + step);
    }

    private void decreaseGlobally(Instant now) {
        if (lastDecrease != null && now.isBefore(lastDecrease.plus(config.decreaseCooldown()))) {
            return;
        }
        limit = Math.max(config.floor(), (int) (limit * config.decreaseFactor()));
        lastDecrease = now;
        lastIncrease = now;
    }

    private void decreaseFirmCap(FirmId firmId, Instant now) {
        FirmPenalty penalty = penalties.get(firmId);
        if (penalty != null && now.isBefore(penalty.since.plus(config.decreaseCooldown()))) {
            return;
        }
        int lowered = Math.max(1, (int) (capOf(firmId, now) * config.decreaseFactor()));
        if (lowered >= config.perFirmCap()) {
            return;
        }
        penalties.put(firmId, new FirmPenalty(lowered, now));
    }

    private void forgetRecoveredFirms(Instant now) {
        penalties.values().removeIf(penalty -> recoveredCap(penalty, now) >= config.perFirmCap());
    }

    private void forgetOldTrouble(Instant now) {
        recentTrouble.values().removeIf(at -> !now.isBefore(at.plus(config.decreaseCooldown())));
    }

    private int capOf(FirmId firmId) {
        return capOf(firmId, clock.instant());
    }

    /** The firm's cap now; forgets the firm if it has recovered fully. */
    private int capOf(FirmId firmId, Instant now) {
        FirmPenalty penalty = penalties.get(firmId);
        if (penalty == null) {
            return config.perFirmCap();
        }
        int cap = recoveredCap(penalty, now);
        if (cap >= config.perFirmCap()) {
            penalties.remove(firmId);
            return config.perFirmCap();
        }
        return cap;
    }

    /** Reduced cap plus one increase step per full increase interval since the last decrease. */
    private int recoveredCap(FirmPenalty penalty, Instant now) {
        if (config.increaseInterval().isZero()) {
            return config.perFirmCap();
        }
        long intervals;
        try {
            intervals = Duration.between(penalty.since, now).dividedBy(config.increaseInterval());
        } catch (ArithmeticException tooManyIntervals) {
            intervals = Long.MAX_VALUE;
        }
        long steps = Math.min(Math.max(intervals, 0), config.perFirmCap());
        return (int) Math.min(config.perFirmCap(), penalty.cap + steps * config.increaseStep());
    }

    public synchronized int currentLimit() {
        return limit;
    }

    private Duration clamp(Duration pause) {
        if (pause.isNegative()) {
            return Duration.ZERO;
        }
        return pause.compareTo(config.maxPause()) > 0 ? config.maxPause() : pause;
    }

    private boolean isPaused() {
        return clock.instant().isBefore(pausedUntil);
    }

    private synchronized void release(FirmId firmId) {
        inFlight--;
        inFlightByFirm.computeIfPresent(firmId, (firm, count) -> count == 1 ? null : count - 1);
    }

    /** A firm's cap right after its last decrease, and when that was. */
    private static final class FirmPenalty {

        private final int cap;
        private final Instant since;

        private FirmPenalty(int cap, Instant since) {
            this.cap = cap;
            this.since = since;
        }
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
