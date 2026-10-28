package net.vectora.cloud.download.jre;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code release} file in a Java home ({@code JAVA_VERSION="21.0.13"}, {@code OS_ARCH="x86_64"}).
 * Reading it tells the Java version without starting a JVM. Every JDK and JRE since Java 8 has it.
 *
 * @param values the keys and their values, without quotes
 */
public record JavaReleaseFile(Map<String, String> values) {

    /** Name of the file in the Java home. */
    public static final String FILE_NAME = "release";

    /** Largest file read; real ones are below 2 KiB. */
    static final int MAX_BYTES = 64 * 1024;

    private static final Pattern LINE = Pattern.compile("([A-Z][A-Z0-9_]{0,63})=(.*)");
    private static final Pattern VERSION = Pattern.compile("(1\\.([0-9]{1,2})|([1-9][0-9]{0,1}))([._+-].*)?");

    /**
     * Copies the values.
     *
     * @param values the values
     */
    public JavaReleaseFile {
        values = Map.copyOf(values);
    }

    /**
     * Parses the content. Unknown lines are ignored; nothing here throws.
     *
     * @param content the file content
     * @return the parsed file
     */
    public static JavaReleaseFile parse(String content) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String raw : content.split("\r?\n|\r", -1)) {
            Matcher m = LINE.matcher(raw.strip());
            if (!m.matches()) {
                continue;
            }
            String value = m.group(2).strip();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (value.chars().noneMatch(Character::isISOControl)) {
                values.putIfAbsent(m.group(1), value);
            }
        }
        return new JavaReleaseFile(values);
    }

    /**
     * Reads the {@code release} file of a Java home.
     *
     * @param home the Java home
     * @return the parsed file, or empty if there is none or it is larger than 64 KiB
     * @throws IOException if it exists but cannot be read
     */
    public static Optional<JavaReleaseFile> read(Path home) throws IOException {
        Path file = home.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                return Optional.empty();
            }
            return Optional.of(parse(new String(bytes, StandardCharsets.UTF_8)));
        }
    }

    /**
     * Returns {@code JAVA_VERSION}.
     *
     * @return the version, for example {@code 21.0.13} or {@code 1.8.0_472}
     */
    public Optional<String> javaVersion() {
        return Optional.ofNullable(values.get("JAVA_VERSION"));
    }

    /**
     * Returns {@code OS_ARCH}.
     *
     * @return the architecture, for example {@code x86_64}, {@code amd64} or {@code aarch64}
     */
    public Optional<String> osArch() {
        return Optional.ofNullable(values.get("OS_ARCH"));
    }

    /**
     * Returns the feature version from {@code JAVA_VERSION}.
     *
     * @return the feature version, or empty if it is missing or unreadable
     */
    public OptionalInt feature() {
        return javaVersion().map(JavaReleaseFile::featureOf).orElse(OptionalInt.empty());
    }

    /**
     * Reads the feature version from a version string: {@code 21.0.13} and {@code 21} give 21,
     * {@code 1.8.0_472} gives 8, {@code 26-ea} gives 26.
     *
     * @param version the version text
     * @return the feature version, or empty if the text is no Java version
     */
    public static OptionalInt featureOf(String version) {
        if (version == null) {
            return OptionalInt.empty();
        }
        Matcher m = VERSION.matcher(version.strip());
        if (!m.matches()) {
            return OptionalInt.empty();
        }
        int feature;
        try {
            feature = Integer.parseInt(m.group(2) != null ? m.group(2) : m.group(3));
        } catch (NumberFormatException e) {
            // Cannot happen with at most two digits; kept so the method never throws.
            return OptionalInt.empty();
        }
        return feature < 1 ? OptionalInt.empty() : OptionalInt.of(feature);
    }
}
