package com.tuv.fanout.domain;

public enum ItemStatus {
    READY,
    LEASED,
    DONE,
    FAILED,
    SKIPPED_OFFER_CHANGED;

    public boolean isTerminal() {
        return this != READY && this != LEASED;
    }
}
