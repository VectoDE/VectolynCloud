package net.vectolyn.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Java runtime of a group: a fixed major version or {@code auto} (the cloud picks the JRE that the
 * Minecraft version needs, from the Mojang manifest).
 *
 * @param major Java major version, or 0 for {@code auto}
 */
public record JavaSelection(int major) {

    /** The cloud chooses. */
    public static final JavaSelection AUTO = new JavaSelection(0);

    /** Lowest major version that can be selected. */
    public static final int MIN_MAJOR = 8;

    /** Highest major version that can be selected. */
    public static final int MAX_MAJOR = 99;

    /** Text form, for error messages. */
    public static final String FORMAT = "auto or a Java major version from 8 to 99";

    /** Checks the range. */
    public JavaSelection {
        if (major != 0 && (major < MIN_MAJOR || major > MAX_MAJOR)) {
            throw new IllegalArgumentException("java major version out of range");
        }
    }

    /** Parses {@code auto} or a major version such as {@code 21}. */
    public static JavaSelection parse(String text) {
        if ("auto".equals(text)) {
            return AUTO;
        }
        if (text == null || !text.matches("[1-9][0-9]?")) {
            throw new IllegalArgumentException("java must be auto or a major version");
        }
        return of(Integer.parseInt(text));
    }

    /** Reads the file value: the text {@code auto} or a whole number. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static JavaSelection fromFile(Object value) {
        if (value instanceof String text && text.equals("auto")) {
            return AUTO;
        }
        if (value instanceof Integer number && number != 0) {
            return of(number);
        }
        throw new IllegalArgumentException("java must be auto or a major version");
    }

    /** A fixed major version. */
    public static JavaSelection of(int major) {
        if (major == 0) {
            throw new IllegalArgumentException("use AUTO");
        }
        return new JavaSelection(major);
    }

    /** Whether the cloud chooses. */
    public boolean isAuto() {
        return major == 0;
    }

    /** {@code "auto"} or the major version as a number. */
    @JsonValue
    public Object fileValue() {
        return isAuto() ? "auto" : Integer.valueOf(major);
    }

    @Override
    public String toString() {
        return isAuto() ? "auto" : Integer.toString(major);
    }
}
