package com.tuv.fanout.support;

/** Runs the {@code ItemStore} contract against the in-memory reference implementation. */
class InMemoryItemStoreTest extends ItemStoreContractTest {

    @Override
    protected InspectableItemStore createStore() {
        return new InMemoryItemStore();
    }
}
