package com.tuv.fanout.domain;

/**
 * Identifies the tenant (accounting firm) that owns an engagement. Restricted to
 * {@code [A-Za-z0-9._-]{1,128}}, so it can be joined into item ids and logged safely.
 */
public record FirmId(String value) implements Comparable<FirmId> {

    public FirmId {
        Require.identifier(value, "firm id");
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
