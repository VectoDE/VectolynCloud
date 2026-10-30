package net.vectolyn.cloud.backup.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class CronExpressionTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private static ZonedDateTime at(String text) {
        return ZonedDateTime.parse(text);
    }

    private static String next(String cron, String after) {
        return CronExpression.parse(cron).next(at(after)).orElseThrow().toString();
    }

    @Test
    void dailyRunsAtTheGivenTime() {
        assertEquals("2026-10-29T04:00Z[UTC]", next("0 4 * * *", "2026-10-29T03:15Z[UTC]"));
        assertEquals("2026-10-30T04:00Z[UTC]", next("0 4 * * *", "2026-10-29T04:00Z[UTC]"), "strictly after");
        assertEquals("2026-10-30T04:00Z[UTC]", next("0 4 * * *", "2026-10-29T04:00:30Z[UTC]"));
    }

    @Test
    void stepsRangesAndLists() {
        assertEquals("2026-10-29T06:00Z[UTC]", next("0 */6 * * *", "2026-10-29T03:15Z[UTC]"));
        assertEquals("2026-10-29T03:30Z[UTC]", next("0,30 * * * *", "2026-10-29T03:15Z[UTC]"));
        assertEquals("2026-10-29T10:05Z[UTC]", next("5 10-12/2 * * *", "2026-10-29T03:15Z[UTC]"));
        assertEquals("2026-11-01T00:00Z[UTC]", next("0 0 1 * *", "2026-10-29T03:15Z[UTC]"));
        assertEquals("2027-01-01T00:00Z[UTC]", next("0 0 1 1 *", "2026-10-29T03:15Z[UTC]"));
    }

    @Test
    void weekdaysAndSundayAsZeroOrSeven() {
        // 2026-10-29 is a Thursday.
        assertEquals("2026-11-01T03:30Z[UTC]", next("30 3 * * 0", "2026-10-29T03:15Z[UTC]"));
        assertEquals("2026-11-01T03:30Z[UTC]", next("30 3 * * 7", "2026-10-29T03:15Z[UTC]"));
        assertEquals("2026-10-30T03:30Z[UTC]", next("30 3 * * 1-5", "2026-10-29T03:30Z[UTC]"));
    }

    @Test
    void dayOfMonthOrWeekdayWhenBothAreRestricted() {
        // The 15th or a Monday, whichever comes first: Monday 2026-11-02.
        assertEquals("2026-11-02T00:00Z[UTC]", next("0 0 15 * 1", "2026-10-29T03:15Z[UTC]"));
        // Counter-check: only the day of month.
        assertEquals("2026-11-15T00:00Z[UTC]", next("0 0 15 * *", "2026-10-29T03:15Z[UTC]"));
    }

    @Test
    void leapDayIsFound() {
        assertEquals("2028-02-29T00:00Z[UTC]", next("0 0 29 2 *", "2026-10-29T03:15Z[UTC]"));
    }

    @Test
    void timeInADaylightSavingGapIsSkipped() {
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        // 2027-03-28 02:30 does not exist in Berlin.
        ZonedDateTime after = ZonedDateTime.of(2027, 3, 27, 12, 0, 0, 0, berlin);
        assertEquals(ZonedDateTime.of(2027, 3, 29, 2, 30, 0, 0, berlin),
                CronExpression.parse("30 2 * * *").next(after).orElseThrow());
        // Counter-check: in UTC the time exists.
        assertEquals(ZonedDateTime.of(2027, 3, 28, 2, 30, 0, 0, UTC),
                CronExpression.parse("30 2 * * *").next(ZonedDateTime.of(2027, 3, 27, 12, 0, 0, 0, UTC)).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "* * * *", "* * * * * *", "60 * * * *", "* 24 * * *", "* * 0 * *", "* * 32 * *",
        "* * * 13 *", "* * * * 8", "5-1 * * * *", "*/0 * * * *", "a * * * *", "1,,2 * * * *", "-1 * * * *",
        "0 0 31 2 *", "0 0 30 2 *", "@daily", "0 4 * * MON", "100 * * * *", "1-2-3 * * * *", "*/ * * * *"})
    void invalidExpressionsAreRefused(String text) {
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse(text));
    }

    @Test
    void tooLongTextIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("0 ".repeat(200)));
    }
}
