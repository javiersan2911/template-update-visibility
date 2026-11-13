package com.tuv.fanout.schedule;

import com.tuv.fanout.domain.FirmId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Round robin across firms: decides who is offered the next free slot, giving each firm
 * with work one offer per cycle, so a firm with 40,000 items does not queue a firm with 40
 * behind it. This is deficit round robin with a quantum of one item, which is all that is
 * needed while every item has the same cost.
 *
 * <p>It only orders offers. It cannot stop a firm from holding slots: if a firm's items run
 * for ever, each slot it takes is never freed and it ratchets up to the whole pool. Bounding
 * what one firm may hold is the capacity limiter's job (its fair share of the global limit).
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
