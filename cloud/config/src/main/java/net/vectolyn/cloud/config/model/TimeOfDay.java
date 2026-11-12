// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A time of day in whole minutes, written {@code HH:mm} with 24 hours: {@code 18:00}, {@code 07:30}.
 *
 * @param time the time, whole minutes
 */
public record TimeOfDay(LocalTime time) {

    /** Text form, for error messages. */
    public static final String FORMAT = "HH:mm with 24 hours, for example 18:30";

    /** The pattern of the text form, also used by the JSON schema. */
    public static final String PATTERN = "([01][0-9]|2[0-3]):[0-5][0-9]";

    private static final Pattern TEXT = Pattern.compile(PATTERN);

    /** Checks that the time has no seconds. */
    public TimeOfDay {
        Objects.requireNonNull(time, "time");
        if (time.getSecond() != 0 || time.getNano() != 0) {
            throw new IllegalArgumentException("time of day must be whole minutes");
        }
    }

    /** Parses {@code HH:mm}, {@code 00:00} to {@code 23:59}. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static TimeOfDay parse(String text) {
        if (text == null || !TEXT.matcher(text).matches()) {
            throw new IllegalArgumentException("time of day must look like 18:30");
        }
        return new TimeOfDay(LocalTime.of(Integer.parseInt(text.substring(0, 2)), Integer.parseInt(text.substring(3))));
    }

    /** Shorthand for a time. */
    public static TimeOfDay of(int hour, int minute) {
        return new TimeOfDay(LocalTime.of(hour, minute));
    }

    /** Text form {@code HH:mm}. */
    @JsonValue
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%02d:%02d", time.getHour(), time.getMinute());
    }
}
