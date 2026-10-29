package net.vectora.cloud.database.sql;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version of a database server, parsed from the JDBC metadata or {@code SELECT VERSION()}. The text
 * comes from the server, so the parser accepts anything and simply finds nothing in garbage.
 *
 * @param major major version
 * @param minor minor version
 * @param patch patch version
 * @param mariaDb whether the server is MariaDB (otherwise MySQL or compatible)
 */
public record ServerVersion(int major, int minor, int patch, boolean mariaDb) {

    /** Longest version string looked at. */
    static final int MAX_LENGTH = 256;

    private static final Pattern NUMBER = Pattern.compile("(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})");
    /** Old MariaDB servers put {@code 5.5.5-} in front of their version for replication clients. */
    private static final String REPLICATION_PREFIX = "5.5.5-";

    /**
     * Parses a version string such as {@code 11.8.9-MariaDB-log}, {@code 5.5.5-10.11.6-MariaDB} or
     * {@code 8.4.3}.
     *
     * @param text the version string, may be {@code null}
     * @return the version, or empty if the text holds none
     */
    public static Optional<ServerVersion> parse(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        String t = text.strip();
        boolean mariaDb = t.toLowerCase(Locale.ROOT).contains("mariadb");
        if (mariaDb && t.startsWith(REPLICATION_PREFIX)) {
            t = t.substring(REPLICATION_PREFIX.length());
        }
        Matcher m = NUMBER.matcher(t);
        if (!m.lookingAt()) {
            return Optional.empty();
        }
        // The pattern allows at most four ASCII digits per part, so parseInt cannot fail; the catch
        // keeps the "never throws" contract explicit.
        try {
            return Optional.of(new ServerVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), mariaDb));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Tells whether this version is at least {@code wantedMajor.wantedMinor}.
     *
     * @param wantedMajor major version
     * @param wantedMinor minor version
     * @return whether this version is the same or newer
     */
    public boolean atLeast(int wantedMajor, int wantedMinor) {
        return major > wantedMajor || (major == wantedMajor && minor >= wantedMinor);
    }

    @Override
    public String toString() {
        return (mariaDb ? "MariaDB " : "MySQL ") + major + "." + minor + "." + patch;
    }
}
