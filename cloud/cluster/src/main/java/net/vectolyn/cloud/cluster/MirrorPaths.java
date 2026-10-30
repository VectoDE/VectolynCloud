package net.vectolyn.cloud.cluster;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.io.ConfigMappers;
import net.vectolyn.cloud.config.validation.ConfigNames;

/**
 * Which paths the mirror carries and the limits of what it carries. The head and the member use
 * the same rules: the head offers nothing else, the member accepts nothing else.
 *
 * <p>A path is relative, with {@code /} between its segments, and must be usable on every system
 * the cloud runs on: no {@code ..}, {@code .} or empty segment, no leading {@code /}, no
 * backslash, colon, control character or {@code <>"|?*}, no segment that ends in a dot or space,
 * no Windows device name ({@code CON}, {@code NUL}, {@code COM1}, ... also with an extension), at
 * most {@link #MAX_PATH_BYTES} bytes and {@link #MAX_DEPTH} segments.
 */
public final class MirrorPaths {

    /** Longest path in UTF-8 bytes. */
    public static final int MAX_PATH_BYTES = 240;
    /** Longest segment of a path in UTF-8 bytes. */
    public static final int MAX_SEGMENT_BYTES = 120;
    /** Most segments of a path. */
    public static final int MAX_DEPTH = 16;

    /** Most mirrored config files ({@code nodes.yml}, groups and tasks together). */
    public static final int MAX_CONFIG_FILES = 1024;
    /** Largest mirrored config file, the limit of the config reader. */
    public static final long MAX_CONFIG_FILE_BYTES = ConfigMappers.MAX_FILE_BYTES;
    /** Largest mirrored config in total. */
    public static final long MAX_CONFIG_BYTES = 32L * 1024 * 1024;

    /** Most templates. */
    public static final int MAX_TEMPLATES = 256;
    /** Most files of one template. */
    public static final int MAX_TEMPLATE_FILES = 20_000;
    /** Largest file of a template. */
    public static final long MAX_TEMPLATE_FILE_BYTES = 1024L * 1024 * 1024;
    /** Largest template in total. */
    public static final long MAX_TEMPLATE_BYTES = 4L * 1024 * 1024 * 1024;
    /** Largest total of all templates. */
    public static final long MAX_TEMPLATES_BYTES = 16L * 1024 * 1024 * 1024;

    private static final Pattern TEMPLATE = Pattern.compile(ConfigNames.TEMPLATE);
    private static final Pattern DEVICE = Pattern.compile("(?i)(con|prn|aux|nul|com[0-9¹²³]|lpt[0-9¹²³])(\\..*)?");
    private static final Set<String> SECRET_NAMES = Set.of("forwarding.secret", "key.pem", "vectolyn.env", ".env");

    private MirrorPaths() {
    }

    /**
     * Whether a path is one the mirror may carry and a member may write (see the class comment).
     *
     * @param path the path, from the network or a file walk
     * @return whether it is safe
     */
    public static boolean isSafePath(String path) {
        if (path == null || path.isEmpty() || path.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES) {
            return false;
        }
        String[] segments = path.split("/", -1);
        if (segments.length > MAX_DEPTH) {
            return false;
        }
        for (String segment : segments) {
            if (!isSafeSegment(segment)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSafeSegment(String segment) {
        if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                || segment.getBytes(StandardCharsets.UTF_8).length > MAX_SEGMENT_BYTES) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            boolean brokenSurrogate = Character.isSurrogate(c) && !validSurrogate(segment, i);
            if (c < 0x20 || c == 0x7f || "\\:<>\"|?*".indexOf(c) >= 0 || brokenSurrogate) {
                return false;
            }
        }
        char last = segment.charAt(segment.length() - 1);
        return last != '.' && last != ' ' && !DEVICE.matcher(segment).matches();
    }

    private static boolean validSurrogate(String text, int index) {
        char c = text.charAt(index);
        if (Character.isHighSurrogate(c)) {
            return index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1));
        }
        return index > 0 && Character.isHighSurrogate(text.charAt(index - 1));
    }

    /**
     * Whether a name is a template the mirror carries: a folder name as {@code ConfigNames.TEMPLATE}
     * allows, which is also a safe path segment.
     */
    public static boolean isTemplateName(String name) {
        return name != null && TEMPLATE.matcher(name).matches() && isSafeSegment(name);
    }

    /**
     * Whether a template file looks like a secret, which the head never offers: {@code forwarding.secret},
     * {@code key.pem}, {@code vectolyn.env}, any {@code *.env} or {@code *.secret}, and anything in a
     * folder named {@code secrets}.
     *
     * @param path relative path inside the template
     */
    public static boolean looksSecret(String path) {
        String[] segments = path.toLowerCase(Locale.ROOT).split("/", -1);
        for (int i = 0; i < segments.length - 1; i++) {
            if (segments[i].equals("secrets")) {
                return true;
            }
        }
        String name = segments[segments.length - 1];
        return SECRET_NAMES.contains(name) || name.endsWith(".env") || name.endsWith(".secret");
    }

    /**
     * Whether a config file is mirrored: exactly {@code nodes.yml}, {@code groups/<name>.yml} and
     * {@code tasks/<name>.yml}. Never {@code cloud.yml}, {@code monitor.yml}, {@code backup.yml},
     * {@code software.lock.yml}, {@code cluster.yml} or anything from {@code secrets/}.
     *
     * @param path relative to {@code config/}
     */
    public static boolean isMirroredConfig(String path) {
        if (path == null) {
            return false;
        }
        if (path.equals(CloudHome.NODES_FILE)) {
            return true;
        }
        for (String folder : new String[] {CloudHome.GROUPS_DIR, CloudHome.TASKS_DIR}) {
            String prefix = folder + "/";
            if (path.startsWith(prefix) && path.endsWith(CloudHome.EXTENSION)) {
                String name = path.substring(prefix.length(), path.length() - CloudHome.EXTENSION.length());
                if (ConfigNames.isValidName(name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
