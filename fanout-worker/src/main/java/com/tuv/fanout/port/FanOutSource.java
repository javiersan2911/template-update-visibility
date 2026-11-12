package com.tuv.fanout.port;

import com.tuv.fanout.domain.EngagementId;
import com.tuv.fanout.domain.FirmId;
import com.tuv.fanout.domain.VersionPin;
import java.util.stream.Stream;

/**
 * Resolves which engagements a published version fans out to. Kept pluggable so the same
 * worker can serve bulk apply, backfill acquisition or any other per-engagement job.
 */
@FunctionalInterface
public interface FanOutSource {

    /** The caller closes the stream; implementations may hold a cursor open behind it. */
    Stream<Target> targetsFor(VersionPin pin);

    record Target(FirmId firmId, EngagementId engagementId) {
    }
}
