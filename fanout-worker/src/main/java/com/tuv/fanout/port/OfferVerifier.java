package com.tuv.fanout.port;

import com.tuv.fanout.domain.EngagementId;
import com.tuv.fanout.domain.VersionPin;

/** Answers, at dispatch time, whether the pinned version is still what the engagement is offered. */
@FunctionalInterface
public interface OfferVerifier {

    boolean isStillOffered(EngagementId engagementId, VersionPin pin);
}
