package com.tuv.fanout.domain;

/**
 * Identifies one engagement. Restricted to {@code [A-Za-z0-9._-]{1,128}}, so it can be joined
 * into item ids and logged safely.
 */
public record EngagementId(String value) {

    public EngagementId {
        Require.identifier(value, "engagement id");
    }

    @Override
    public String toString() {
        return value;
    }
}
