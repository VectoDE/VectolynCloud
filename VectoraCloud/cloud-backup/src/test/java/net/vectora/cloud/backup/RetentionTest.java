package net.vectora.cloud.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.vectora.cloud.backup.Retention.Candidate;
import net.vectora.cloud.backup.config.RetentionConfig;
import org.junit.jupiter.api.Test;

class RetentionTest {

    private static final Instant START = Instant.parse("2026-01-01T03:00:00Z");

    /** One backup every {@code step}, ids b00, b01, ... oldest first. */
    private static List<Candidate> backups(int count, Duration step) {
        List<Candidate> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new Candidate(String.format("b%02d", i), START.plus(step.multipliedBy(i))));
        }
        return list;
    }

    private static Set<String> keep(List<Candidate> backups, int last, int daily, int weekly, int monthly) {
        return new TreeSet<>(Retention.keep(backups, new RetentionConfig(last, daily, weekly, monthly), ZoneOffset.UTC));
    }

    @Test
    void keepLastKeepsTheNewest() {
        assertEquals(Set.of("b07", "b08", "b09"), keep(backups(10, Duration.ofHours(1)), 3, 0, 0, 0));
        assertEquals(Set.of("b00", "b01"), keep(backups(2, Duration.ofHours(1)), 5, 0, 0, 0), "fewer than n: all");
    }

    @Test
    void dailyKeepsTheNewestOfEachDay() {
        // Four backups per day for three days.
        List<Candidate> list = backups(12, Duration.ofHours(6));
        assertEquals(Set.of("b03", "b07", "b11"), keep(list, 1, 3, 0, 0));
        assertEquals(Set.of("b07", "b11"), keep(list, 1, 2, 0, 0));
    }

    @Test
    void weeklyAndMonthlyCountCalendarPeriods() {
        List<Candidate> daily = backups(70, Duration.ofDays(1));
        // 2026-01-01 is a Thursday; the newest backup is Wednesday 2026-03-11 (b69). Sundays: b66, b59.
        Set<String> weekly = keep(daily, 1, 0, 3, 0);
        assertEquals(3, weekly.size());
        assertEquals(Set.of("b69", "b66", "b59"), weekly);
        Set<String> monthly = keep(daily, 1, 0, 0, 3);
        // Newest of March (b69), February (b58 = 2026-02-28) and January (b30 = 2026-01-31).
        assertEquals(Set.of("b30", "b58", "b69"), monthly);
    }

    @Test
    void rulesAddUp() {
        List<Candidate> daily = backups(70, Duration.ofDays(1));
        Set<String> union = keep(daily, 2, 3, 0, 3);
        assertEquals(Set.of("b69", "b68", "b67", "b58", "b30"), union);
    }

    @Test
    void resultDoesNotDependOnTheInputOrder() {
        List<Candidate> list = backups(30, Duration.ofHours(7));
        Set<String> expected = keep(list, 3, 4, 2, 1);
        List<Candidate> shuffled = new ArrayList<>(list);
        Collections.shuffle(shuffled, new java.util.Random(42));
        assertEquals(expected, keep(shuffled, 3, 4, 2, 1));
    }

    @Test
    void daysFollowTheZone() {
        // 23:30 UTC on Jan 1 and 00:30 UTC on Jan 2: two days in UTC, one day in UTC-2.
        List<Candidate> list = List.of(new Candidate("a", Instant.parse("2026-01-01T23:30:00Z")),
                new Candidate("b", Instant.parse("2026-01-02T00:30:00Z")));
        RetentionConfig policy = new RetentionConfig(1, 2, 0, 0);
        assertEquals(Set.of("a", "b"), Retention.keep(list, policy, ZoneOffset.UTC));
        assertEquals(Set.of("b"), Retention.keep(list, policy, ZoneId.of("-02:00")));
    }
}
