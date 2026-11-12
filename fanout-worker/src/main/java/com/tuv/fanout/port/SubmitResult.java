package com.tuv.fanout.port;

import java.time.Duration;
import java.util.Objects;

public sealed interface SubmitResult {

    /** The work was accepted now, or had been accepted earlier under the same key. */
    record Accepted() implements SubmitResult {
    }

    /** The downstream system is at capacity and did not take the work (HTTP 429). */
    record Throttled(Duration retryAfter) implements SubmitResult {

        /** A missing or negative hint means "no specific wait requested". */
        public Throttled {
            retryAfter = retryAfter == null || retryAfter.isNegative() ? Duration.ZERO : retryAfter;
        }
    }

    /**
     * The downstream system will never accept this command.
     *
     * @param kind   what the worker should do about it; the reason is free text for people
     * @param reason human-readable explanation, kept in the item's detail
     */
    record Rejected(Kind kind, String reason) implements SubmitResult {

        public Rejected {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(reason, "reason");
        }

        public enum Kind {
            /** The pinned version no longer applies to the engagement: the item is skipped, not failed. */
            VERSION_NOT_APPLICABLE,
            /** Any other refusal: the item fails. */
            OTHER
        }
    }
}
