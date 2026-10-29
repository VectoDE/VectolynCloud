package net.vectora.cloud.backup.manifest;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Rules for paths inside a backup archive. They must be safe to unpack on Windows and Linux alike,
 * whatever the archive or the manifest claims:
 *
 * <ul>
 *   <li>relative, {@code /}-separated, 1 to {@value #MAX_LENGTH} characters, at most
 *       {@value #MAX_DEPTH} parts;</li>
 *   <li>no empty part, no {@code .} or {@code ..} (no traversal);</li>
 *   <li>no {@code \ : * ? " < > |} and no control characters (backslash and colon would change
 *       the meaning on Windows: separator and drive or stream);</li>
 *   <li>no part ending in a space or dot, no Windows device name ({@code con}, {@code nul},
 *       {@code com1}, {@code lpt1}, ... also with an extension).</li>
 * </ul>
 */
public final class ManifestPaths {

    /** Longest path. */
    public static final int MAX_LENGTH = 1024;
    /** Most parts. */
    public static final int MAX_DEPTH = 64;
    /** Folder of the service files. */
    public static final String SERVICES = "services/";
    /** Folder of the database dumps. */
    public static final String DATABASE = "database/";

    private static final Pattern DEVICE = Pattern.compile("(con|prn|aux|nul|com[0-9¹²³]|lpt[0-9¹²³]|conin\\$|conout\\$)");

    private ManifestPaths() {
    }

    /**
     * Whether {@code path} follows every rule.
     *
     * @param path the path
     * @return whether it is safe
     */
    public static boolean isSafe(String path) {
        if (path == null || path.isEmpty() || path.length() > MAX_LENGTH) {
            return false;
        }
        String[] parts = path.split("/", -1);
        if (parts.length > MAX_DEPTH) {
            return false;
        }
        for (String part : parts) {
            if (!isSafePart(part)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether one part of a path is safe.
     *
     * @param part the part
     * @return whether it is safe
     */
    public static boolean isSafePart(String part) {
        if (part.isEmpty() || part.length() > 255 || part.equals(".") || part.equals("..")) {
            return false;
        }
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c < 0x20 || c == 0x7f || "\\:*?\"<>|".indexOf(c) >= 0) {
                return false;
            }
        }
        char last = part.charAt(part.length() - 1);
        if (last == ' ' || last == '.') {
            return false;
        }
        int dot = part.indexOf('.');
        String stem = (dot < 0 ? part : part.substring(0, dot)).strip().toLowerCase(Locale.ROOT);
        return !DEVICE.matcher(stem).matches();
    }

    /** {@code services/<id>/}. */
    public static String servicePrefix(String serviceId) {
        return SERVICES + serviceId + "/";
    }

    /** Key to detect two paths that are the same file on a case-insensitive file system. */
    static String foldKey(String path) {
        return path.toLowerCase(Locale.ROOT);
    }
}
