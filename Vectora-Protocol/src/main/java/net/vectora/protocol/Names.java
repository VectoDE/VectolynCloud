package net.vectora.protocol;

import java.util.Objects;
import java.util.regex.Pattern;

/** Shared checks of the identifier value types. */
final class Names {

    /**
     * Letters, digits, {@code _} and {@code -}, starting with a letter or digit. No dots or
     * slashes, because these names end up in file and directory names.
     */
    static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

    private Names() {
    }

    /** Checks {@code value} against {@code pattern} and the length limit, returns it unchanged. */
    static String check(String value, Pattern pattern, int maxLength, String what) {
        Objects.requireNonNull(value, what);
        if (value.isEmpty() || value.length() > maxLength) {
            throw new IllegalArgumentException(what + " must be 1 to " + maxLength + " characters long");
        }
        if (!pattern.matcher(value).matches()) {
            // The value itself is left out: it may come from the network and contain control characters.
            throw new IllegalArgumentException(what + " does not match " + pattern.pattern());
        }
        return value;
    }
}
