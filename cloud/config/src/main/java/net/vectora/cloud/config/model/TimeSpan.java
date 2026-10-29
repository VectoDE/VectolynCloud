package net.vectora.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A duration written in whole seconds, minutes or hours: {@code 30s}, {@code 5m}, {@code 1h}.
 *
 * @param duration the duration, 0 to {@link #MAX}, whole seconds
 */
public record TimeSpan(Duration duration) {

    /** Upper limit, 7 days. */
    public static final Duration MAX = Duration.ofDays(7);

    /** Text form, for error messages. */
    public static final String FORMAT = "<number>s, <number>m or <number>h, for example 30s";

    private static final Pattern TEXT = Pattern.compile("(0|[1-9][0-9]{0,5})([smh])");

    /** Checks the range. */
    public TimeSpan {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.compareTo(MAX) > 0 || duration.getNano() != 0) {
            throw new IllegalArgumentException("time span out of range");
        }
    }

    /** Parses {@code 30s}, {@code 5m} or {@code 1h}. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static TimeSpan parse(String text) {
        Matcher m = TEXT.matcher(text == null ? "" : text);
        if (!m.matches()) {
            throw new IllegalArgumentException("time span must look like 30s, 5m or 1h");
        }
        long value = Long.parseLong(m.group(1));
        return new TimeSpan(switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(value);
            case "m" -> Duration.ofMinutes(value);
            default -> Duration.ofHours(value);
        });
    }

    /** Shorthand for a span in seconds. */
    public static TimeSpan ofSeconds(long seconds) {
        return new TimeSpan(Duration.ofSeconds(seconds));
    }

    /** Text form in the largest unit that divides evenly. */
    @JsonValue
    @Override
    public String toString() {
        long seconds = duration.getSeconds();
        if (seconds != 0 && seconds % 3600 == 0) {
            return seconds / 3600 + "h";
        }
        if (seconds != 0 && seconds % 60 == 0) {
            return seconds / 60 + "m";
        }
        return seconds + "s";
    }
}
