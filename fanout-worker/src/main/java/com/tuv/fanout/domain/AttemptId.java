package com.tuv.fanout.domain;

/**
 * Idempotency key for one downstream attempt. The downstream system deduplicates on it, so
 * sending the same key twice has the effect of sending it once.
 */
public record AttemptId(String value) {

    public AttemptId {
        Require.nonBlank(value, "attempt id");
    }

    @Override
    public String toString() {
        return value;
    }
}
