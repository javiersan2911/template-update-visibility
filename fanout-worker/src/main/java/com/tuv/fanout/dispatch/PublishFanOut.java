package com.tuv.fanout.dispatch;

import com.tuv.fanout.domain.VersionPin;
import com.tuv.fanout.domain.WorkItem;
import com.tuv.fanout.port.FanOutSource;
import com.tuv.fanout.port.FanOutSource.Target;
import com.tuv.fanout.port.ItemIntake;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Turns one publish into durable work items. Item ids are derived from the firm, the pin and
 * the engagement, so handling the same publish event again, or resuming after a crash halfway
 * through, adds only the items that are still missing.
 */
public final class PublishFanOut {

    private final FanOutSource source;
    private final ItemIntake store;
    private final int batchSize;

    public PublishFanOut(FanOutSource source, ItemIntake store, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batch size must be positive");
        }
        this.source = Objects.requireNonNull(source, "source");
        this.store = Objects.requireNonNull(store, "store");
        this.batchSize = batchSize;
    }

    /** @return the number of items newly created */
    public int onPublished(VersionPin pin) {
        int created = 0;
        try (Stream<Target> targets = source.targetsFor(pin)) {
            Iterator<Target> iterator = targets.iterator();
            List<WorkItem> batch = new ArrayList<>(batchSize);
            while (iterator.hasNext()) {
                Target target = iterator.next();
                batch.add(WorkItem.create(target.firmId(), target.engagementId(), pin));
                if (batch.size() == batchSize) {
                    created += store.enqueue(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                created += store.enqueue(batch);
            }
        }
        return created;
    }
}
