// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatisticsCollectorTest {

    private static final class MovingClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static ModeStatistics mode(List<ModeStatistics> list, String name) {
        return list.stream().filter(m -> m.mode().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void countsLoginsPlaytimeAndPeakPerMode(@TempDir Path dir) throws Exception {
        MovingClock clock = new MovingClock();
        StatisticsCollector stats = StatisticsCollector.open(dir.resolve("s.json"), clock);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        stats.arrived(a, "lobby");
        clock.advance(Duration.ofSeconds(60));
        stats.arrived(b, "lobby");
        clock.advance(Duration.ofSeconds(30));
        stats.arrived(a, "bedwars"); // a moves: 90 s lobby, a new login in bedwars
        clock.advance(Duration.ofSeconds(100));
        stats.left(a);
        stats.left(b);

        List<ModeStatistics> list = stats.snapshot();
        assertEquals(new ModeStatistics("lobby", 2, 90 + 130, 2), mode(list, "lobby"));
        assertEquals(new ModeStatistics("bedwars", 1, 100, 1), mode(list, "bedwars"));
    }

    @Test
    void snapshotIncludesPlayersWhoAreStillOnline(@TempDir Path dir) throws Exception {
        MovingClock clock = new MovingClock();
        StatisticsCollector stats = StatisticsCollector.open(dir.resolve("s.json"), clock);
        stats.arrived(UUID.randomUUID(), "lobby");
        clock.advance(Duration.ofSeconds(10));
        assertEquals(10, mode(stats.snapshot(), "lobby").playtimeSeconds());
        clock.advance(Duration.ofSeconds(5));
        assertEquals(15, mode(stats.snapshot(), "lobby").playtimeSeconds());
    }

    @Test
    void savedFileHoldsTotalsOnlyNoPlayerData(@TempDir Path dir) throws Exception {
        MovingClock clock = new MovingClock();
        Path file = dir.resolve("state/s.json");
        StatisticsCollector stats = StatisticsCollector.open(file, clock);
        UUID player = UUID.randomUUID();
        stats.arrived(player, "lobby");
        clock.advance(Duration.ofSeconds(20));
        stats.closeAll();
        stats.save();
        String content = Files.readString(file);
        assertFalse(content.contains(player.toString()));
        assertEquals(new ModeStatistics("lobby", 1, 20, 1),
                mode(StatisticsCollector.open(file, clock).snapshot(), "lobby"));
    }

    @Test
    void invalidModeNamesAreIgnoredAndAUnknownLeaveIsHarmless(@TempDir Path dir) throws Exception {
        StatisticsCollector stats = StatisticsCollector.open(dir.resolve("s.json"), new MovingClock());
        stats.arrived(UUID.randomUUID(), "Lobby One!");
        stats.left(UUID.randomUUID());
        assertTrue(stats.snapshot().isEmpty());
    }

    @Test
    void damagedFileIsRefused() {
        assertThrows(StatisticsCollector.FormatException.class,
                () -> StatisticsCollector.decode(("{\"version\":1,\"modes\":[{\"mode\":\"a\",\"logins\":-1,"
                        + "\"playtime-seconds\":0,\"peak-players\":0}]}").getBytes(StandardCharsets.UTF_8)));
        assertThrows(StatisticsCollector.FormatException.class,
                () -> StatisticsCollector.decode("[]".getBytes(StandardCharsets.UTF_8)));
    }
}
