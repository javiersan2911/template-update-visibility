package com.tuv.fanout.domain;

/** Argument checks shared by the value objects. */
final class Require {

    private Require() {
    }

    static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
