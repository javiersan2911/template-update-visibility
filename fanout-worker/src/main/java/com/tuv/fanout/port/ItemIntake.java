package com.tuv.fanout.port;

import com.tuv.fanout.domain.WorkItem;
import java.util.Collection;

/** The producer's view of the item store. */
public interface ItemIntake {

    /**
     * Adds items that do not exist yet; existing item ids are left untouched. The
     * collection is not retained after the call returns.
     *
     * @return the number of items actually inserted
     */
    int enqueue(Collection<WorkItem> items);
}
