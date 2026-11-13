package com.tuv.fanout.support;

import com.tuv.fanout.domain.ItemId;
import com.tuv.fanout.domain.ItemStatus;
import com.tuv.fanout.domain.WorkItem;
import com.tuv.fanout.port.ItemStore;

/**
 * An {@link ItemStore} that tests can also look into. The port has no read side, so the
 * contract tests need this to observe an item's state; a production store's test adapter
 * implements it with a query.
 */
public interface InspectableItemStore extends ItemStore {

    ItemStatus statusOf(ItemId id);

    WorkItem itemOf(ItemId id);
}
