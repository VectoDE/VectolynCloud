package net.vectolyn.cloud.download.jre;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The properties a JVM prints with {@code java -XshowSettings:properties -version}. Used when a
 * Java on the {@code PATH} has no {@code release} file next to it (for example a launcher stub).
 *
 * @param properties the single-line properties, such as {@code java.home} and {@code java.version}
 */
public record JavaSettings(Map<String, String> properties) {

    /** Largest output read from the JVM. */
    static final int MAX_CHARS = 256 * 1024;

    // "    java.home = /usr/lib/jvm/temurin-21": four spaces, a key, " = ", the value. Values that
    // span several lines (paths) continue with deeper indentation and are ignored.
    private static final Pattern LINE = Pattern.compile(" {4}([a-z][a-zA-Z0-9._-]{0,127}) = (.*)");

    /**
     * Copies the values.
     *
     * @param properties the properties
     */
    public JavaSettings {
        properties = Map.copyOf(properties);
    }

    /**
     * Parses the output. Lines that are no property are ignored; nothing here throws.
     *
     * @param output what the JVM printed (standard error and standard output together)
     * @return the parsed settings
     */
    public static JavaSettings parse(String output) {
        String text = output.length() > MAX_CHARS ? output.substring(0, MAX_CHARS) : output;
        Map<String, String> properties = new LinkedHashMap<>();
        for (String line : text.split("\r?\n|\r", -1)) {
            Matcher m = LINE.matcher(line);
            if (m.matches()) {
                String value = m.group(2).strip();
                if (value.chars().noneMatch(Character::isISOControl)) {
                    properties.putIfAbsent(m.group(1), value);
                }
            }
        }
        return new JavaSettings(properties);
    }

    /**
     * Returns {@code java.home}.
     *
     * @return the Java home, or empty
     */
    public Optional<String> home() {
        return Optional.ofNullable(properties.get("java.home")).filter(s -> !s.isEmpty());
    }

    /**
     * Returns {@code java.version}.
     *
     * @return the version, or empty
     */
    public Optional<String> version() {
        return Optional.ofNullable(properties.get("java.version")).filter(s -> !s.isEmpty());
    }

    /**
     * Returns {@code os.arch}.
     *
     * @return the architecture, or empty
     */
    public Optional<String> arch() {
        return Optional.ofNullable(properties.get("os.arch")).filter(s -> !s.isEmpty());
    }

    /**
     * Returns the feature version from {@code java.specification.version} ({@code 21}, or {@code 1.8}
     * on Java 8), else from {@code java.version}.
     *
     * @return the feature version, or empty
     */
    public OptionalInt feature() {
        OptionalInt spec = JavaReleaseFile.featureOf(properties.get("java.specification.version"));
        return spec.isPresent() ? spec : JavaReleaseFile.featureOf(properties.get("java.version"));
    }
}
