package com.tuv.fanout.schedule;

import com.tuv.fanout.domain.FirmId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Round robin across firms: each firm with work gets one item per cycle, so a firm with
 * 40,000 items cannot starve a firm with 40. This is deficit round robin with a quantum of
 * one item, which is all that is needed while every item has the same cost.
 *
 * <p>Not thread-safe; it is confined to the dispatcher thread.
 */
public final class RoundRobinScheduler implements FairScheduler {

    private FirmId lastServed;

    @Override
    public List<FirmId> order(Set<FirmId> candidates) {
        TreeSet<FirmId> sorted = new TreeSet<>(candidates);
        if (lastServed == null) {
            return List.copyOf(sorted);
        }
        List<FirmId> ordered = new ArrayList<>(sorted.tailSet(lastServed, false));
        ordered.addAll(sorted.headSet(lastServed, true));
        return ordered;
    }

    @Override
    public void served(FirmId firmId) {
        lastServed = firmId;
    }
}
