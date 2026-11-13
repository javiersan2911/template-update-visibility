package com.tuv.fanout.domain;

/**
 * Identity of one unit of fan-out work, {@code firm/product@seq/engagement}. It is derived
 * from the firm, the pin and the engagement, so a redelivered publish event maps onto the
 * items that already exist instead of creating duplicates, and the same engagement id under
 * two firms is two items. The parts cannot contain {@code /} or {@code @}, so no two
 * distinct tuples join to the same value.
 *
 * <p>The constructor accepts only that joined form: three identifier parts (the alphabet of
 * {@link FirmId}) and a canonical non-negative sequence number, so an id read back from a
 * store cannot carry control characters into the logs or an unbounded string into memory.
 * {@link WorkItem} additionally checks that the id belongs to the item's own firm, pin and
 * engagement.
 */
public record ItemId(String value) {

    /** Three parts of at most 128 characters, a sequence of at most 19 digits, and three separators. */
    private static final int MAX_LENGTH = 3 * 128 + 19 + 3;

    public ItemId {
        if (value == null || value.length() > MAX_LENGTH || !isJoinedForm(value)) {
            throw new IllegalArgumentException("item id must be firm/product@seq/engagement");
        }
    }

    public static ItemId of(FirmId firmId, VersionPin pin, EngagementId engagementId) {
        return new ItemId(firmId + "/" + pin + "/" + engagementId);
    }

    /** Splits at the first and last {@code /} and the first {@code @}; a stray separator lands in a part and fails it. */
    private static boolean isJoinedForm(String value) {
        int firstSlash = value.indexOf('/');
        int lastSlash = value.lastIndexOf('/');
        if (firstSlash < 0 || firstSlash == lastSlash) {
            return false;
        }
        String pin = value.substring(firstSlash + 1, lastSlash);
        int at = pin.indexOf('@');
        return at >= 0
                && Require.isIdentifier(value.substring(0, firstSlash))
                && Require.isIdentifier(pin.substring(0, at))
                && isCanonicalSequence(pin.substring(at + 1))
                && Require.isIdentifier(value.substring(lastSlash + 1));
    }

    /** What {@code VersionPin.toString()} renders: ASCII digits of a {@code long}, no sign, no leading zeros. */
    private static boolean isCanonicalSequence(String seq) {
        if (seq.isEmpty() || seq.length() > 19) {
            return false;
        }
        for (int i = 0; i < seq.length(); i++) {
            if (seq.charAt(i) < '0' || seq.charAt(i) > '9') {
                return false;
            }
        }
        try {
            return Long.toString(Long.parseLong(seq)).equals(seq);
        } catch (NumberFormatException overflow) {
            return false;
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
