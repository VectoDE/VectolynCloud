package net.vectolyn.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A JVM heap size in whole megabytes, written like the {@code -Xms}/{@code -Xmx} values:
 * {@code 512M}, {@code 2G}.
 *
 * @param megabytes size in MiB, 1 to {@link #MAX_MEGABYTES}
 */
public record MemorySize(long megabytes) {

    /** Upper limit, 1 TiB. */
    public static final long MAX_MEGABYTES = 1024L * 1024L;

    /** Text form, for error messages. */
    public static final String FORMAT = "<number>M or <number>G, for example 512M";

    private static final Pattern TEXT = Pattern.compile("([1-9][0-9]{0,6})([MmGg])");

    /** Checks the range. */
    public MemorySize {
        if (megabytes < 1 || megabytes > MAX_MEGABYTES) {
            throw new IllegalArgumentException("memory size out of range");
        }
    }

    /** Parses {@code 512M} or {@code 2G} (unit case-insensitive). */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static MemorySize parse(String text) {
        Matcher m = TEXT.matcher(text == null ? "" : text);
        if (!m.matches()) {
            throw new IllegalArgumentException("memory size must look like 512M or 2G");
        }
        long value = Long.parseLong(m.group(1));
        return new MemorySize(m.group(2).equalsIgnoreCase("G") ? value * 1024L : value);
    }

    /** Shorthand for {@code new MemorySize(megabytes)}. */
    public static MemorySize ofMegabytes(long megabytes) {
        return new MemorySize(megabytes);
    }

    /** Text form for files and JVM flags, in {@code G} when it divides evenly. */
    @JsonValue
    @Override
    public String toString() {
        return megabytes % 1024 == 0
                ? String.format(Locale.ROOT, "%dG", megabytes / 1024)
                : String.format(Locale.ROOT, "%dM", megabytes);
    }
}
