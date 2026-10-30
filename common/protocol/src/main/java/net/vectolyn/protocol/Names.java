package net.vectolyn.protocol;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared checks of the identifier value types.
 *
 * <p>Group names, node ids and service ids end up in file and directory names. Windows and
 * macOS do not tell upper and lower case apart in file names, so only lower-case letters are
 * allowed: {@code Lobby} and {@code lobby} would otherwise be two names but one directory.
 * Windows also reserves the device names {@code con}, {@code prn}, {@code aux}, {@code nul},
 * {@code com0} to {@code com9} and {@code lpt0} to {@code lpt9}; a file or directory with such a
 * name cannot be created there, so these names are rejected as well.
 */
final class Names {

    /**
     * Lower-case ASCII letters, digits, {@code _} and {@code -}, starting with a letter or digit.
     * No dots or slashes, because these names end up in file and directory names.
     */
    static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9][a-z0-9_-]*");

    /** Names Windows reserves for devices. Compared exactly, the pattern allows lower case only. */
    static final Set<String> DEVICE_NAMES = deviceNames();

    private Names() {
    }

    /**
     * Checks an identifier: {@link #IDENTIFIER}, the length limit and no {@link #DEVICE_NAMES
     * device name}. Returns it unchanged.
     */
    static String checkIdentifier(String value, int maxLength, String what) {
        check(value, IDENTIFIER, maxLength, what);
        if (DEVICE_NAMES.contains(value)) {
            throw new IllegalArgumentException(what + " must not be a Windows device name");
        }
        return value;
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

    private static Set<String> deviceNames() {
        String[] names = new String[4 + 2 * 10];
        names[0] = "con";
        names[1] = "prn";
        names[2] = "aux";
        names[3] = "nul";
        for (int i = 0; i <= 9; i++) {
            names[4 + i] = "com" + i;
            names[14 + i] = "lpt" + i;
        }
        return Set.of(names);
    }
}
