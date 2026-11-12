package com.tuv.fanout.domain;

/**
 * Identity of one unit of fan-out work. It is derived from the pin and the engagement, so
 * a redelivered publish event maps onto the items that already exist instead of creating
 * duplicates.
 */
public record ItemId(String value) {

    public ItemId {
        Require.nonBlank(value, "item id");
    }

    public static ItemId of(VersionPin pin, EngagementId engagementId) {
        return new ItemId(pin + "/" + engagementId);
    }

    @Override
    public String toString() {
        return value;
    }
}
