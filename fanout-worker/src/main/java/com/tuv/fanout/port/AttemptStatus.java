package com.tuv.fanout.port;

public enum AttemptStatus {
    IN_PROGRESS,
    SUCCEEDED,
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
    /** The downstream system has no record of the key: the submit never arrived. */
    NOT_FOUND
}
