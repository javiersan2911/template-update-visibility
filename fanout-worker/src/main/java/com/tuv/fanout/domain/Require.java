package com.tuv.fanout.domain;

import java.util.regex.Pattern;

/** Argument checks shared by the value objects. */
final class Require {

    /**
     * Characters allowed in an id that is joined into item ids and written to logs. It
     * excludes the separators {@code / @ #}, whitespace and control characters, so a joined
     * form can be read back in only one way and cannot forge a log line.
     */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private Require() {
    }

    static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** The message names the rule, never the rejected value, which may be hostile. */
    static String identifier(String value, String name) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must match " + IDENTIFIER.pattern());
        }
        return value;
    }
}
