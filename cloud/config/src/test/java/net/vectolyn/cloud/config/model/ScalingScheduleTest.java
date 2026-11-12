// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** When a schedule is active, at its edges, and what it does to the minimum of a task. */
class ScalingScheduleTest {

    /** 2026-11-13 is a Friday. */
    private static LocalDateTime friday(int hour, int minute) {
        return LocalDateTime.of(2026, 11, 13, hour, minute);
    }

    @Test
    void aWindowWithinADayStartsAtFromAndEndsBeforeTo() {
        ScalingSchedule s = new ScalingSchedule(List.of(Weekday.FRI), TimeOfDay.of(18, 0), TimeOfDay.of(22, 0), 3);
        assertFalse(s.activeAt(friday(17, 59)));
        assertTrue(s.activeAt(friday(18, 0)), "from is inclusive");
        assertTrue(s.activeAt(friday(21, 59)));
        assertFalse(s.activeAt(friday(22, 0)), "to is exclusive");
        assertFalse(s.activeAt(friday(19, 0).plusDays(1)), "Saturday is not listed");
        assertFalse(s.activeAt(friday(19, 0).minusDays(1)), "Thursday is not listed");
    }

    @Test
    void aWindowPastMidnightBelongsToTheDayItStarts() {
        ScalingSchedule s = new ScalingSchedule(List.of(Weekday.FRI), TimeOfDay.of(22, 0), TimeOfDay.of(2, 0), 3);
        assertFalse(s.activeAt(friday(21, 59)));
        assertTrue(s.activeAt(friday(22, 0)));
        assertTrue(s.activeAt(friday(23, 59)));
        LocalDateTime saturday = friday(0, 0).plusDays(1);
        assertEquals(DayOfWeek.SATURDAY, saturday.getDayOfWeek());
        assertTrue(s.activeAt(saturday), "Friday's window goes on after midnight");
        assertTrue(s.activeAt(saturday.withHour(1).withMinute(59)));
        assertFalse(s.activeAt(saturday.withHour(2)));
        assertFalse(s.activeAt(saturday.withHour(22)), "Saturday's own evening is not listed");
        assertFalse(s.activeAt(friday(1, 0)), "Thursday's window is not listed");
    }

    @Test
    void fromEqualToToLastsAWholeDayAndMidnightAsToEndsTheDay() {
        ScalingSchedule day = new ScalingSchedule(List.of(Weekday.SUN), TimeOfDay.of(0, 0), TimeOfDay.of(0, 0), 2);
        LocalDateTime sunday = friday(0, 0).plusDays(2);
        assertTrue(day.activeAt(sunday));
        assertTrue(day.activeAt(sunday.withHour(23).withMinute(59)));
        assertFalse(day.activeAt(sunday.plusDays(1)));
        assertFalse(day.activeAt(sunday.minusMinutes(1)));

        ScalingSchedule evening = new ScalingSchedule(List.of(Weekday.FRI), TimeOfDay.of(18, 0), TimeOfDay.of(0, 0), 2);
        assertTrue(evening.activeAt(friday(23, 59)));
        assertFalse(evening.activeAt(friday(0, 0).plusDays(1)), "ends at midnight");
    }

    @Test
    void theMinimumIsTheHighestActiveScheduleAtMostMaxServices() {
        ScalingConfig base = ScalingConfig.counts(1, 1, 4);
        ScalingConfig scheduled = base.withSchedules(List.of(
                new ScalingSchedule(List.of(Weekday.FRI), TimeOfDay.of(18, 0), TimeOfDay.of(23, 0), 3),
                new ScalingSchedule(List.of(Weekday.FRI), TimeOfDay.of(20, 0), TimeOfDay.of(21, 0), 4),
                new ScalingSchedule(List.of(Weekday.FRI), TimeOfDay.of(19, 0), TimeOfDay.of(23, 0), 0)));
        assertEquals(1, scheduled.minServicesAt(friday(17, 0)));
        assertEquals(3, scheduled.minServicesAt(friday(18, 0)));
        assertEquals(4, scheduled.minServicesAt(friday(20, 30)), "the highest of the active ones");
        assertEquals(3, scheduled.minServicesAt(friday(22, 0)), "a lower schedule never lowers min-services");
        assertEquals(1, base.minServicesAt(friday(20, 30)));
        // A file edited past the validator cannot push the minimum above max-services.
        assertEquals(4, base.withSchedules(List.of(new ScalingSchedule(List.of(Weekday.FRI), TimeOfDay.of(0, 0),
                TimeOfDay.of(0, 0), 9))).minServicesAt(friday(12, 0)));
    }

    @Test
    void timesAreWholeMinutesWithTwentyFourHours() {
        assertEquals(TimeOfDay.of(0, 0), TimeOfDay.parse("00:00"));
        assertEquals(TimeOfDay.of(23, 59), TimeOfDay.parse("23:59"));
        assertEquals("07:05", TimeOfDay.parse("07:05").toString());
        for (String bad : new String[] {"24:00", "7:05", "07:60", "07:5", "07:05:00", "", " 07:05"}) {
            assertThrows(IllegalArgumentException.class, () -> TimeOfDay.parse(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> TimeOfDay.parse(null));
        for (DayOfWeek day : DayOfWeek.values()) {
            assertEquals(day, Weekday.of(day).day());
        }
    }
}
