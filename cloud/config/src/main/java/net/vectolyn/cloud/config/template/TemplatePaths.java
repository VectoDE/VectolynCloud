// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config.template;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import net.vectolyn.cloud.config.validation.ConfigNames;

/**
 * Which paths inside {@code local/templates/} the cloud hands out or accepts from the network. The
 * cluster mirror and the template endpoints of the API use the same rules, so a file the API may
 * write is a file the mirror may carry, and nothing else.
 *
 * <p>A path is relative, with {@code /} between its segments, and must be usable on every system
 * the cloud runs on: no {@code ..}, {@code .} or empty segment, no leading {@code /}, no
 * backslash, colon, {@code <>"|?*}, no control, format (bidi, zero-width) or private-use
 * character, no separator other than the space, no segment that ends in a dot or space,
 * no Windows device name ({@code CON}, {@code NUL}, {@code COM1}, ... also with an extension), at
 * most {@link #MAX_PATH_BYTES} bytes and {@link #MAX_DEPTH} segments.
 */
public final class TemplatePaths {

    /** Longest path in UTF-8 bytes. */
    public static final int MAX_PATH_BYTES = 240;
    /** Longest segment of a path in UTF-8 bytes. */
    public static final int MAX_SEGMENT_BYTES = 120;
    /** Most segments of a path. */
    public static final int MAX_DEPTH = 16;

    private static final Pattern TEMPLATE = Pattern.compile(ConfigNames.TEMPLATE);
    private static final Pattern DEVICE = Pattern.compile("(?i)(con|prn|aux|nul|com[0-9¹²³]|lpt[0-9¹²³])(\\..*)?");
    private static final Set<String> SECRET_NAMES = Set.of("forwarding.secret", "key.pem", "vectolyn.env", ".env",
            "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", ".netrc", ".git-credentials", ".pgpass");
    private static final List<String> SECRET_SUFFIXES = List.of(".env", ".secret", ".pem", ".key", ".p12", ".pfx",
            ".jks", ".keystore", ".kdbx");
    private static final Set<String> SECRET_FOLDERS = Set.of("secrets", ".git", ".ssh", ".gnupg");

    private TemplatePaths() {
    }

    /**
     * Whether a path is one the cloud may hand out or write (see the class comment).
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
            if (Character.isSurrogate(c) && !validSurrogate(segment, i)) {
                return false;
            }
            if ("\\:<>\"|?*".indexOf(c) >= 0) {
                return false;
            }
        }
        // No control (C0, DEL, C1), format (bidi overrides, zero-width), separator other than the
        // space or private-use character: names reach logs and the console. Unassigned characters
        // are not refused: which ones those are depends on the Unicode version of the JDK, and head
        // and member must agree on every path.
        for (int i = 0; i < segment.length(); ) {
            int cp = segment.codePointAt(i);
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.PRIVATE_USE
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || (type == Character.SPACE_SEPARATOR && cp != ' ')) {
                return false;
            }
            i += Character.charCount(cp);
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
     * Whether a name is a template: a folder name as {@code ConfigNames.TEMPLATE} allows, which is
     * also a safe path segment.
     *
     * @param name the folder name
     * @return whether it is a template name
     */
    public static boolean isTemplateName(String name) {
        return name != null && TEMPLATE.matcher(name).matches() && isSafeSegment(name);
    }

    /**
     * Whether a template file looks like a secret, which the cloud never hands out or accepts:
     * {@code forwarding.secret}, {@code vectolyn.env}, SSH and credential files ({@code id_rsa},
     * {@code .netrc}, ...), any {@code .env.*}, any name ending in {@code .env}, {@code .secret},
     * {@code .pem}, {@code .key}, {@code .p12}, {@code .pfx}, {@code .jks}, {@code .keystore} or
     * {@code .kdbx}, also with a further extension ({@code forwarding.secret.bak}), and anything in a
     * folder {@link #isSecretFolder}. Case is ignored. Secrets inside an ordinary file (an RCON
     * password in {@code server.properties}) are not detected; they do not belong in a template.
     *
     * @param path relative path inside the template
     * @return whether it looks like a secret
     */
    public static boolean looksSecret(String path) {
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length - 1; i++) {
            if (isSecretFolder(segments[i])) {
                return true;
            }
        }
        String name = segments[segments.length - 1].toLowerCase(Locale.ROOT);
        if (SECRET_NAMES.contains(name) || name.startsWith(".env.")) {
            return true;
        }
        for (String suffix : SECRET_SUFFIXES) {
            if (name.endsWith(suffix) || name.contains(suffix + ".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a folder of a template is left out as a whole: {@code secrets}, {@code .git},
     * {@code .ssh} and {@code .gnupg}, case ignored.
     *
     * @param name the folder's name
     * @return whether it is left out
     */
    public static boolean isSecretFolder(String name) {
        return SECRET_FOLDERS.contains(name.toLowerCase(Locale.ROOT));
    }
}
