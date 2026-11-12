package com.tuv.fanout.port;

import java.time.Duration;

public sealed interface SubmitResult {

    /** The work was accepted now, or had been accepted earlier under the same key. */
    record Accepted() implements SubmitResult {
    }

    /** The downstream system is at capacity and did not take the work (HTTP 429). */
    record Throttled(Duration retryAfter) implements SubmitResult {

        /** A missing hint means "no specific wait requested". */
        public Throttled {
            retryAfter = retryAfter == null ? Duration.ZERO : retryAfter;
        }
    }

    /** The downstream system will never accept this command, for example a version mismatch. */
    record Rejected(String reason) implements SubmitResult {
    }
}
