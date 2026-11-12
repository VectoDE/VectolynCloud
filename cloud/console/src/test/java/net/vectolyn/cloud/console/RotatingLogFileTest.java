// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import net.vectolyn.cloud.config.model.LogsConfig;
import net.vectolyn.cloud.config.model.MemorySize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RotatingLogFileTest {

    private static final Instant NOON = Instant.parse("2026-11-12T12:00:00Z");

    /** A clock the test moves forward. */
    private static final class FakeClock extends Clock {
        private Instant now;

        FakeClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void openingRenamesTheFileOfTheLastStartByItsDate(@TempDir Path dir) throws IOException {
        FakeClock clock = new FakeClock(NOON);
        try (RotatingLogFile log = RotatingLogFile.open(dir, "cloud", LogsConfig::defaults, clock)) {
            log.write("run 1\n");
        }
        Files.setLastModifiedTime(dir.resolve("cloud.log"), FileTime.from(NOON));
        try (RotatingLogFile log = RotatingLogFile.open(dir, "cloud", LogsConfig::defaults, clock)) {
            log.write("run 2\n");
        }
        Files.setLastModifiedTime(dir.resolve("cloud.log"), FileTime.from(NOON));
        try (RotatingLogFile log = RotatingLogFile.open(dir, "cloud", LogsConfig::defaults, clock)) {
            log.write("run 3\n");
        }
        assertEquals(List.of("cloud-2026-11-12.1.log", "cloud-2026-11-12.log", "cloud.log"), names(dir));
        assertEquals("run 1\n", Files.readString(dir.resolve("cloud-2026-11-12.log")));
        assertEquals("run 2\n", Files.readString(dir.resolve("cloud-2026-11-12.1.log")));
        assertEquals("run 3\n", Files.readString(dir.resolve("cloud.log")));
    }

    @Test
    void aNewFileBeginsAtMidnightAndNeverInsideALine(@TempDir Path dir) throws IOException {
        FakeClock clock = new FakeClock(NOON);
        try (RotatingLogFile log = RotatingLogFile.open(dir, "lobby-1", LogsConfig::defaults, clock)) {
            log.write("first day\nhalf ");
            clock.advance(Duration.ofHours(13));
            log.write("a line\n");
            log.write("second day\n");
        }
        assertEquals(List.of("lobby-1-2026-11-12.log", "lobby-1.log"), names(dir));
        assertEquals("first day\nhalf a line\n", Files.readString(dir.resolve("lobby-1-2026-11-12.log")));
        assertEquals("second day\n", Files.readString(dir.resolve("lobby-1.log")));
    }

    @Test
    void aNewFileBeginsAtTheMaximumSize(@TempDir Path dir) throws IOException {
        FakeClock clock = new FakeClock(NOON);
        LogsConfig small = LogsConfig.defaults().withMaxFileSize(MemorySize.ofMegabytes(1));
        String line = "x".repeat(1023) + "\n";
        try (RotatingLogFile log = RotatingLogFile.open(dir, "cloud", () -> small, clock)) {
            for (int i = 0; i < 1024 + 10; i++) {
                log.write(line);
            }
        }
        assertEquals(List.of("cloud-2026-11-12.log", "cloud.log"), names(dir));
        assertEquals(1024L * 1024L, Files.size(dir.resolve("cloud-2026-11-12.log")));
        assertEquals(10L * 1024L, Files.size(dir.resolve("cloud.log")));
    }

    @Test
    void filesOlderThanTheRetentionAreDeleted(@TempDir Path dir) throws IOException {
        FakeClock clock = new FakeClock(NOON);
        Files.writeString(dir.resolve("cloud-2026-10-28.log"), "15 days ago");
        Files.writeString(dir.resolve("cloud-2026-10-29.log"), "14 days ago");
        Files.writeString(dir.resolve("cloud-2026-10-01.3.log"), "old, third of its day");
        Files.writeString(dir.resolve("cloud.4.log"), "former scheme, old");
        Files.setLastModifiedTime(dir.resolve("cloud.4.log"), FileTime.from(NOON.minus(Duration.ofDays(30))));
        Files.writeString(dir.resolve("cloud.1.log"), "former scheme, recent");
        Files.setLastModifiedTime(dir.resolve("cloud.1.log"), FileTime.from(NOON.minus(Duration.ofDays(2))));
        Files.writeString(dir.resolve("other-2026-01-01.log"), "another base");
        Files.writeString(dir.resolve("notes.txt"), "not a log");
        try (RotatingLogFile log = RotatingLogFile.open(dir, "cloud", LogsConfig::defaults, clock)) {
            log.write("now\n");
        }
        assertEquals(List.of("cloud-2026-10-29.log", "cloud.1.log", "cloud.log", "notes.txt",
                "other-2026-01-01.log"), names(dir));

        // Every base when no base is given; the current files stay.
        assertEquals(1, RotatingLogFile.prune(dir, null, 14, clock));
        assertFalse(Files.exists(dir.resolve("other-2026-01-01.log")));
        assertTrue(Files.exists(dir.resolve("cloud.log")));
    }

    @Test
    void aChangedRetentionCountsFromTheNextRotation(@TempDir Path dir) throws IOException {
        FakeClock clock = new FakeClock(NOON);
        AtomicReference<LogsConfig> settings = new AtomicReference<>(LogsConfig.defaults());
        Files.writeString(dir.resolve("cloud-2026-11-05.log"), "a week ago");
        try (RotatingLogFile log = RotatingLogFile.open(dir, "cloud", settings::get, clock)) {
            log.write("today\n");
            assertTrue(Files.exists(dir.resolve("cloud-2026-11-05.log")));
            settings.set(LogsConfig.defaults().withRetentionDays(3));
            clock.advance(Duration.ofDays(1));
            log.write("tomorrow\n");
        }
        assertEquals(List.of("cloud-2026-11-12.log", "cloud.log"), names(dir));
    }

    @Test
    void brokenSettingsFallBackToTheDefaults(@TempDir Path dir) throws IOException {
        FakeClock clock = new FakeClock(NOON);
        Files.writeString(dir.resolve("cloud-2026-11-01.log"), "eleven days ago");
        try (RotatingLogFile log = RotatingLogFile.open(dir, "cloud", () -> {
            throw new IllegalStateException("config not readable");
        }, clock)) {
            log.write("x\n");
        }
        assertTrue(Files.exists(dir.resolve("cloud-2026-11-01.log")));
    }

    @Test
    void aClosedFileRejectsLinesAndANameWithASlashIsRefused(@TempDir Path dir) throws IOException {
        FakeClock clock = new FakeClock(NOON);
        RotatingLogFile log = RotatingLogFile.open(dir, "cloud", LogsConfig::defaults, clock);
        log.close();
        log.close();
        assertThrows(IOException.class, () -> log.write("late\n"));
        assertThrows(IllegalArgumentException.class,
                () -> RotatingLogFile.open(dir, "../cloud", LogsConfig::defaults, clock));
    }
}
