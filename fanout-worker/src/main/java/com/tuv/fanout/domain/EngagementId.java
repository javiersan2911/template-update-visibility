package com.tuv.fanout.domain;

public record EngagementId(String value) {

    public EngagementId {
        Require.nonBlank(value, "engagement id");
    }

    @Override
    public String toString() {
        return value;
    }
}
