package com.tuv.fanout.dispatch;

/**
 * A port call did not return within the configured {@code callTimeout}. Unchecked, so it
 * lands in the same paths as any other exception a port throws: for a submit the outcome is
 * unknown, for a status read the status is unreadable, for a lease call the firm cools off.
 */
final class CallTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    CallTimeoutException(String message) {
        super(message);
    }
}
