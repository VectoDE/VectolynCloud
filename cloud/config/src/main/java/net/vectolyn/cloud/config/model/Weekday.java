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
        return switch (this) {
            case MON -> DayOfWeek.MONDAY;
            case TUE -> DayOfWeek.TUESDAY;
            case WED -> DayOfWeek.WEDNESDAY;
            case THU -> DayOfWeek.THURSDAY;
            case FRI -> DayOfWeek.FRIDAY;
            case SAT -> DayOfWeek.SATURDAY;
            case SUN -> DayOfWeek.SUNDAY;
        };
    }

    /**
     * The weekday of a {@code java.time} day.
     *
     * @param day the day
     * @return the weekday
     */
    public static Weekday of(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> MON;
            case TUESDAY -> TUE;
            case WEDNESDAY -> WED;
            case THURSDAY -> THU;
            case FRIDAY -> FRI;
            case SATURDAY -> SAT;
            case SUNDAY -> SUN;
        };
    }
}
