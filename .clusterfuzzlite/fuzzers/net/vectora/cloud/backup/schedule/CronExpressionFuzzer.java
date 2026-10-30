package net.vectora.cloud.backup.schedule;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * ClusterFuzzLite harness for {@link CronExpression#parse(String)}. Only {@link IllegalArgumentException}
 * may escape, and an accepted expression fires strictly later each time.
 */
public final class CronExpressionFuzzer {

    private static final ZonedDateTime START =
            ZonedDateTime.of(2026, 10, 25, 1, 59, 30, 0, ZoneId.of("Europe/Berlin"));

    private CronExpressionFuzzer() {
    }

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        String text = data.consumeRemainingAsString();
        CronExpression cron;
        try {
            cron = CronExpression.parse(text);
        } catch (IllegalArgumentException expected) {
            return;
        }
        ZonedDateTime after = START;
        for (int i = 0; i < 3; i++) {
            ZonedDateTime next = cron.next(after).orElseThrow(() -> new IllegalStateException("never fires: " + text));
            if (!next.isAfter(after)) {
                throw new IllegalStateException("not strictly after: " + text);
            }
            after = next;
        }
    }
}
