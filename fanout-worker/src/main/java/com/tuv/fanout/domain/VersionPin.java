package com.tuv.fanout.domain;

/**
 * The published template version a fan-out is pinned to. Every item re-checks this pin
 * immediately before the downstream call, so a later publish or a withdrawal never causes
 * a stale version to be applied.
 */
public record VersionPin(String productId, long versionSeq) {

    public VersionPin {
        Require.nonBlank(productId, "product id");
        if (versionSeq < 0) {
            throw new IllegalArgumentException("version sequence must not be negative");
        }
    }

    @Override
    public String toString() {
        return productId + "@" + versionSeq;
    }
}
