package net.vectolyn.cloud.download;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Checks file names that come from a download API or the lockfile before the cloud uses them on
 * disk. Such a name must be a plain base name: one path element that means the same file on
 * Windows, Linux and macOS.
 */
public final class FileNames {

    /** Longest accepted name, the limit of common file systems. */
    public static final int MAX_LENGTH = 255;

    /**
     * Windows device names, with or without extension. {@code com¹} to {@code com³} and
     * {@code conin$}/{@code conout$} are device names as well.
     */
    private static final Pattern DEVICE_NAME = Pattern.compile(
            "(con|prn|aux|nul|com[0-9¹²³]|lpt[0-9¹²³]|conin\\$|conout\\$)(\\..*)?");

    private FileNames() {
    }

    /**
     * Tells whether a name is a safe base name: 1 to 255 characters, no path separator, no drive
     * colon, no control character, none of {@code * ? " < > |}, not {@code .} or {@code ..}, no
     * trailing dot or space (Windows would drop them) and no Windows device name such as {@code nul}
     * or {@code com1.jar}.
     *
     * @param name the name to check, may be {@code null}
     * @return {@code true} if the name is safe to use as a file name
     */
    public static boolean isSafeBaseName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_LENGTH || name.isBlank()) {
            return false;
        }
        if (name.equals(".") || name.equals("..") || name.endsWith(".") || name.endsWith(" ")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c) || "/\\:*?\"<>|".indexOf(c) >= 0) {
                return false;
            }
        }
        return !DEVICE_NAME.matcher(name.toLowerCase(Locale.ROOT)).matches();
    }

    /**
     * Throws if a name is not a safe base name (see {@link #isSafeBaseName}).
     *
     * @param name the name
     * @param what what the name is, for the message
     * @return the name
     * @throws IllegalArgumentException if the name is not safe
     */
    public static String requireSafeBaseName(String name, String what) {
        if (!isSafeBaseName(name)) {
            throw new IllegalArgumentException(what + " must be a plain file name without path, '..' or device name: "
                    + printable(name));
        }
        return name;
    }

    private static String printable(String name) {
        if (name == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder();
        name.codePoints().limit(80).forEach(cp -> out.appendCodePoint(Character.isISOControl(cp) ? '?' : cp));
        return out.toString();
    }
}
