package com.tuv.fanout.schedule;

import com.tuv.fanout.domain.FirmId;
import java.util.List;
import java.util.Set;

/** Decides in which order firms are offered the next free slot. */
public interface FairScheduler {

    /** The candidates, ordered so the firm that has waited longest comes first. */
    List<FirmId> order(Set<FirmId> candidates);

    /** Records that the firm was just given a slot. */
    void served(FirmId firmId);
}
