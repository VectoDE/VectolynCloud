package net.vectora.cloud.config.validation;

import java.util.Locale;
import java.util.regex.Pattern;

/** Patterns for names and other text values in the configuration. */
public final class ConfigNames {

    /**
     * Group, task and node names: lower case letters, digits, {@code _} and {@code -}, 1 to 32
     * characters, starting with a letter or digit. {@link #isValidName} additionally rejects the
     * Windows device names ({@code con}, {@code prn}, {@code aux}, {@code nul}, {@code com0} to
     * {@code com9}, {@code lpt0} to {@code lpt9}), which cannot be used as file names there.
     *
     * <p>The rule is identical to {@code GroupName} and {@code NodeId} in common/protocol (both 1 to
     * 32 characters, lower case only, no device names), so every configured name can travel on the
     * wire. A service id there ({@code ServiceId}) allows up to 64 characters, so that
     * {@code <group>-<index>} fits. Lower case only, because group and task names are file names and
     * must not collide on case-insensitive file systems (Windows, macOS).
     */
    public static final String NAME = "[a-z0-9][a-z0-9_-]{0,31}";
    /** Network display name. */
    public static final String NETWORK_NAME = "[\\p{L}\\p{N}][\\p{L}\\p{N} ._-]{0,31}";
    /** Template folder below the cloud home. */
    public static final String TEMPLATE = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";
    /** Minecraft version such as {@code 26.3}, {@code 1.8.8} or {@code 1.21.10}. */
    public static final String MINECRAFT_VERSION = "[0-9]{1,3}(\\.[0-9]{1,3}){1,2}";
    /** Catalog id of a plugin or mod. */
    public static final String CATALOG_ID = "[a-z0-9][a-z0-9._-]{0,63}";
    /** Host name or IP address. */
    public static final String HOST = "[A-Za-z0-9:\\[][A-Za-z0-9.:\\-\\]]{0,252}";
    /** Database and database user name. */
    public static final String DATABASE_IDENTIFIER = "[A-Za-z0-9_]{1,64}";
    /** Docker image reference. */
    public static final String DOCKER_IMAGE = "[a-z0-9][a-z0-9._/:@-]{0,254}";
    /** Path below the cloud home, forward slashes, no {@code ..}. */
    public static final String RELATIVE_PATH = "[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*){0,7}";

    private static final Pattern NAME_PATTERN = Pattern.compile(NAME);
    private static final Pattern DEVICE_NAME = Pattern.compile("con|prn|aux|nul|com[0-9]|lpt[0-9]");
    private static final int MAX_ARGUMENT_LENGTH = 64;
    private static final Pattern LOOPBACK_V4 = Pattern.compile("127(\\.[0-9]{1,3}){3}");
    /** A browser origin: lower-case scheme and host, optional port, no path, no trailing slash. */
    private static final Pattern ORIGIN = Pattern.compile(
            "https?://([a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?)*|\\[[0-9a-f:.]{2,45}\\])"
                    + "(:[0-9]{1,5})?");

    /**
     * Tells whether {@code value} is a browser origin for CORS ({@code https://dashboard.example.org},
     * {@code http://localhost:5173}). {@code *}, paths, user info and upper case are refused.
     *
     * @param value the text to check
     * @return whether it is an origin
     */
    public static boolean isOrigin(String value) {
        return value != null && value.length() <= 300 && ORIGIN.matcher(value).matches();
    }

    private ConfigNames() {
    }

    /** Whether {@code name} is a valid group, task or node name (see {@link #NAME}). */
    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches() && !DEVICE_NAME.matcher(name).matches();
    }

    /**
     * Makes user input safe as an error argument (for example an unknown field or group name): a
     * valid name is returned as is; otherwise control and non-ASCII characters become {@code ?} and
     * the text is cut to 64 characters, so it cannot forge or break log lines.
     */
    public static String safeArgument(String value) {
        if (value == null || isValidName(value)) {
            return String.valueOf(value);
        }
        int length = Math.min(value.length(), MAX_ARGUMENT_LENGTH);
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char ch = value.charAt(i);
            out.append(ch >= 0x20 && ch < 0x7f ? ch : '?');
        }
        return out.toString();
    }

    /** Whether {@code host} is a loopback address ({@code localhost}, {@code 127.x.x.x}, {@code ::1}). */
    public static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("::1") || h.equals("[::1]") || LOOPBACK_V4.matcher(h).matches();
    }
}
