package com.tuv.fanout.support;

import com.tuv.fanout.capacity.CapacityFeedback;
import com.tuv.fanout.domain.FirmId;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records every signal, including the pause values, so tests can assert on what was reported. */
public final class RecordingFeedback implements CapacityFeedback {

    private final List<FirmId> successFirms = new CopyOnWriteArrayList<>();
    private final List<Duration> pauses = new CopyOnWriteArrayList<>();
    private final List<FirmId> troubleFirms = new CopyOnWriteArrayList<>();

    @Override
    public void recordSuccess(FirmId firmId) {
        successFirms.add(firmId);
    }

    @Override
    public void recordFirmTrouble(FirmId firmId) {
        troubleFirms.add(firmId);
    }

    @Override
    public void recordOverload(Duration pause) {
        pauses.add(pause);
    }

    public int successes() {
        return successFirms.size();
    }

    /** The firm named in each success signal, in order. */
    public List<FirmId> successFirms() {
        return List.copyOf(successFirms);
    }

    /** Number of global overload signals (HTTP 429). */
    public int overloads() {
        return pauses.size();
    }

    /** The pause passed with each global overload signal, in order. */
    public List<Duration> pauses() {
        return List.copyOf(pauses);
    }

    /** The firm named in each firm-trouble signal, in order. */
    public List<FirmId> troubleFirms() {
        return List.copyOf(troubleFirms);
    }
}
