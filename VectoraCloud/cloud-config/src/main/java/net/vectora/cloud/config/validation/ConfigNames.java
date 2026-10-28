package net.vectora.cloud.config.validation;

import java.util.Locale;
import java.util.regex.Pattern;

/** Patterns for names and other text values in the configuration. */
public final class ConfigNames {

    /**
     * Group, task and node names: lower case letters, digits, {@code _} and {@code -}, 1 to 32
     * characters, starting with a letter or digit. A strict subset of the names Vectora-Protocol
     * accepts ({@code [A-Za-z0-9][A-Za-z0-9_-]*}, groups up to 32, nodes up to 64 characters), so every
     * configured name can travel on the wire. Lower case only, because group and task names are file
     * names and must not collide on case-insensitive file systems (Windows, macOS).
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
    private static final Pattern LOOPBACK_V4 = Pattern.compile("127(\\.[0-9]{1,3}){3}");

    private ConfigNames() {
    }

    /** Whether {@code name} is a valid group, task or node name. */
    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
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
