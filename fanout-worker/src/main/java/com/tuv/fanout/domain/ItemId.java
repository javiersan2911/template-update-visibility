package com.tuv.fanout.domain;

/**
 * Identity of one unit of fan-out work, {@code firm/product@seq/engagement}. It is derived
 * from the firm, the pin and the engagement, so a redelivered publish event maps onto the
 * items that already exist instead of creating duplicates, and the same engagement id under
 * two firms is two items. The parts cannot contain {@code /} or {@code @}, so no two
 * distinct tuples join to the same value.
 */
public record ItemId(String value) {

    public ItemId {
        Require.nonBlank(value, "item id");
    }

    public static ItemId of(FirmId firmId, VersionPin pin, EngagementId engagementId) {
        return new ItemId(firmId + "/" + pin + "/" + engagementId);
    }

    @Override
    public String toString() {
        return value;
    }
}
