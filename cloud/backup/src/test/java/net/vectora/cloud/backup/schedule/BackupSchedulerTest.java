package net.vectora.cloud.backup.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import net.vectora.cloud.backup.BackupService;
import net.vectora.cloud.backup.TestHome;
import net.vectora.cloud.backup.config.RetentionConfig;
import net.vectora.cloud.backup.config.ScheduleConfig;
import net.vectora.cloud.config.model.TimeSpan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-10-29T03:15:00Z");

    @TempDir
    java.nio.file.Path home;

    @Test
    void nextRunForEachKindOfSchedule() {
        assertEquals(Optional.empty(), BackupScheduler.nextRun(ScheduleConfig.disabled(), NOW, Optional.empty(), NOW,
                ZoneOffset.UTC));
        assertEquals(Optional.of(Instant.parse("2026-10-29T04:00:00Z")),
                BackupScheduler.nextRun(ScheduleConfig.cron("0 4 * * *"), NOW, Optional.empty(), NOW, ZoneOffset.UTC));
        ScheduleConfig every6h = ScheduleConfig.every(TimeSpan.parse("6h"));
        assertEquals(Optional.of(NOW.plus(Duration.ofHours(6))),
                BackupScheduler.nextRun(every6h, NOW, Optional.empty(), NOW, ZoneOffset.UTC), "one interval after start");
        Instant last = NOW.minus(Duration.ofHours(10));
        assertEquals(Optional.of(last.plus(Duration.ofHours(6))),
                BackupScheduler.nextRun(every6h, NOW, Optional.of(last), NOW, ZoneOffset.UTC),
                "overdue: due right away");
    }

    @Test
    void tickRunsTheBackupWhenDueAndPrunes() throws Exception {
        TestHome test = new TestHome(home);
        test.addServer("lobby-1", "lobby", "Lobby-Server", true);
        test.config = test.config.withSchedule(ScheduleConfig.every(TimeSpan.parse("1h")))
                .withRetention(new RetentionConfig(2, 0, 0, 0));
        BackupService service = test.service();
        try (BackupScheduler scheduler = new BackupScheduler(service)) {
            scheduler.tick();
            assertEquals(0, service.list().size(), "not due yet");
            assertEquals(Optional.of(NOW.plus(Duration.ofHours(1))), scheduler.nextRun());

            for (int i = 1; i <= 3; i++) {
                test.clock.advance(Duration.ofHours(1));
                scheduler.tick();
            }
            assertEquals(2, service.list().size(), "three runs, retention keeps two");
            assertTrue(service.list().stream().allMatch(b -> b.manifest().orElseThrow().reason().equals("scheduled")));

            // A schedule without a time plans nothing; nothing runs.
            test.config = test.config.withSchedule(new ScheduleConfig(true, null, null));
            test.clock.advance(Duration.ofHours(5));
            scheduler.tick();
            assertEquals(2, service.list().size());

            // Switched off: no next run.
            test.config = test.config.withSchedule(ScheduleConfig.disabled());
            scheduler.tick();
            assertEquals(Optional.empty(), scheduler.nextRun());
        }
    }
}
