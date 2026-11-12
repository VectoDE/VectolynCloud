// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

/**
 * A time window in which a task keeps more services running, one entry of {@code scaling.schedules}
 * of a task. While it is active, the minimum of the task is at least {@code minServices}. The window
 * starts on each of {@code days} at {@code from} and ends at {@code to}. A {@code to} before
 * {@code from} ends on the next day ({@code 22:00} to {@code 02:00}); {@code from} equal to
 * {@code to} lasts a whole day. The rules are in {@code docs/cloud/scaling.md}.
 *
 * @param days        the days the window starts on, at least one
 * @param from        start, inclusive
 * @param to          end, exclusive
 * @param minServices services that run at least while the window is active, 0 to
 *                    {@code max-services}
 */
@JsonPropertyOrder({"days", "from", "to", "min-services"})
public record ScalingSchedule(@JsonProperty(required = true) List<Weekday> days,
                              @JsonProperty(required = true) TimeOfDay from,
                              @JsonProperty(required = true) TimeOfDay to,
                              @JsonProperty(required = true) int minServices) {

    /** Copies the days. */
    public ScalingSchedule {
        days = days == null ? null : List.copyOf(days);
    }

    /**
     * Whether the window is active at a local time.
     *
     * @param at the date and time in the zone of the node
     * @return whether it is active
     */
    public boolean activeAt(LocalDateTime at) {
        Objects.requireNonNull(at, "at");
        if (days == null || from == null || to == null) {
            return false;
        }
        LocalTime time = at.toLocalTime();
        LocalTime start = from.time();
        LocalTime end = to.time();
        boolean today = days.contains(Weekday.of(at.getDayOfWeek()));
        if (start.isBefore(end)) {
            return today && !time.isBefore(start) && time.isBefore(end);
        }
        // Ends on the next day: from today on, or the rest of a window that started yesterday.
        boolean yesterday = days.contains(Weekday.of(at.getDayOfWeek().minus(1)));
        return (today && !time.isBefore(start)) || (yesterday && time.isBefore(end));
    }
}
