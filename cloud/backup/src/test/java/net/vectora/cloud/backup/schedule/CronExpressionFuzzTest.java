package net.vectora.cloud.backup.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Fuzz target for {@link CronExpression#parse(String)}, which reads {@code schedule.cron} from the
 * hand-edited {@code backup.yml}. Nothing but {@link IllegalArgumentException} may escape, and every
 * accepted expression must fire again and again, strictly later each time, also across a daylight
 * saving change. In a normal test run it executes in regression mode (stored inputs only).
 */
class CronExpressionFuzzTest {

    private static final ZonedDateTime[] STARTS = {
        ZonedDateTime.of(2026, 10, 29, 3, 15, 0, 0, ZoneId.of("UTC")),
        // Shortly before the clocks go back and forward in Berlin.
        ZonedDateTime.of(2026, 10, 25, 1, 59, 30, 0, ZoneId.of("Europe/Berlin")),
        ZonedDateTime.of(2027, 3, 28, 1, 59, 0, 0, ZoneId.of("Europe/Berlin")),
    };

    /** The same property for fixed inputs, so a normal run checks it without stored fuzz inputs. */
    @ParameterizedTest
    @ValueSource(strings = {"0 4 * * *", "*/5 * * * *", "30 2 * * *", "0 2-3 * * 0", "59 23 31 12 *", "0 0 29 2 *",
        "0 0 31 2 1", "0 0 30 */2 *", "0-59/7 1,2,3 1-31/3 1-12 0-7", "  0\t4 * *  * ", "0 0 31 4,6,9,11 *",
        "*/61 * * * *", "0 0 * * 8", "x", ""})
    void fixedInputs(String text) {
        parseFailsOnlyWithIllegalArgumentAndAlwaysFires(text);
    }

    @FuzzTest
    void parseFailsOnlyWithIllegalArgumentAndAlwaysFires(@NotNull String text) {
        CronExpression cron;
        try {
            cron = CronExpression.parse(text);
        } catch (IllegalArgumentException expected) {
            return;
        }
        assertEquals(cron, CronExpression.parse(cron.toString()), "the normalised text parses to the same");
        for (ZonedDateTime start : STARTS) {
            ZonedDateTime after = start;
            for (int i = 0; i < 3; i++) {
                ZonedDateTime next = cron.next(after).orElseThrow(() -> new AssertionError("never fires: " + text));
                assertTrue(next.isAfter(after), "not strictly after");
                assertEquals(0, next.getSecond());
                assertEquals(0, next.getNano());
                assertEquals(start.getZone(), next.getZone());
                after = next;
            }
        }
    }
}
