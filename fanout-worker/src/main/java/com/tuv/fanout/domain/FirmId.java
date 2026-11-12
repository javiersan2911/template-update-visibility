package com.tuv.fanout.domain;

/** Identifies the tenant (accounting firm) that owns an engagement. */
public record FirmId(String value) implements Comparable<FirmId> {

    public FirmId {
        Require.nonBlank(value, "firm id");
    }

    @Override
    public int compareTo(FirmId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
