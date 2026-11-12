// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.DayOfWeek;

/** A day of the week in a scaling schedule, written in three lower-case letters. */
public enum Weekday {
    /** Monday. */
    @JsonProperty("mon") MON,
    /** Tuesday. */
    @JsonProperty("tue") TUE,
    /** Wednesday. */
    @JsonProperty("wed") WED,
    /** Thursday. */
    @JsonProperty("thu") THU,
    /** Friday. */
    @JsonProperty("fri") FRI,
    /** Saturday. */
    @JsonProperty("sat") SAT,
    /** Sunday. */
    @JsonProperty("sun") SUN;

    /**
     * The day as {@code java.time} knows it.
     *
     * @return the day
     */
    public DayOfWeek day() {
        return DayOfWeek.of(ordinal() + 1);
    }

    /**
     * The weekday of a {@code java.time} day.
     *
     * @param day the day
     * @return the weekday
     */
    public static Weekday of(DayOfWeek day) {
        return values()[day.getValue() - 1];
    }
}
