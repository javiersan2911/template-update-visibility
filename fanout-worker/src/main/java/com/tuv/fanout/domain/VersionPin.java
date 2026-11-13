package com.tuv.fanout.domain;

/**
 * The published template version a fan-out is pinned to. Every item re-checks this pin
 * immediately before the downstream call, so a later publish or a withdrawal never causes
 * a stale version to be applied. The product id is restricted to {@code [A-Za-z0-9._-]{1,128}},
 * so {@code product@seq} is unambiguous and safe to log.
 */
public record VersionPin(String productId, long versionSeq) {

    public VersionPin {
        Require.identifier(productId, "product id");
        if (versionSeq < 0) {
            throw new IllegalArgumentException("version sequence must not be negative");
        }
    }

    @Override
    public String toString() {
        return productId + "@" + versionSeq;
    }
}
