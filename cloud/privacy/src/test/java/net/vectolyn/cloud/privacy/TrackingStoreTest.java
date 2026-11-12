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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrackingStoreTest {

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

    @Test
    void nothingIsRecordedWithoutConsent(@TempDir Path dir) throws Exception {
        MovingClock clock = new MovingClock();
        Path file = dir.resolve("t.json");
        TrackingStore store = TrackingStore.open(file, 90, clock);
        UUID player = UUID.randomUUID();
        store.arrived(player, "Steve", "lobby");
        clock.advance(Duration.ofSeconds(30));
        store.left(player);
        assertFalse(store.consented(player));
        assertTrue(store.find(player).isEmpty());
        assertEquals(0, store.size());
        assertFalse(Files.exists(file));
    }

    @Test
    void trackingStartsAtTheConsentAndCountsFromThen(@TempDir Path dir) throws Exception {
        MovingClock clock = new MovingClock();
        TrackingStore store = TrackingStore.open(dir.resolve("t.json"), 90, clock);
        UUID player = UUID.randomUUID();
        store.arrived(player, "Steve", "lobby"); // before the consent: not kept
        clock.advance(Duration.ofSeconds(100));
        store.grant(player, "Steve", "lobby");
        clock.advance(Duration.ofSeconds(60));
        store.arrived(player, "Steve", "bedwars");
        clock.advance(Duration.ofSeconds(40));
        TrackedPlayer record = store.find(player).orElseThrow();
        assertEquals(TrackingNotice.VERSION, record.noticeVersion());
        assertEquals(2, record.logins());
        assertEquals(100, record.playtimeSeconds());
        assertEquals(new TrackedPlayer.ModeTotals("bedwars", 1, 40), record.modes().get(0));
        assertEquals(new TrackedPlayer.ModeTotals("lobby", 1, 60), record.modes().get(1));
        assertEquals(Instant.parse("2026-01-01T00:01:40Z"), record.consentAt());
    }

    @Test
    void revokeDeletesEverythingAtOnce(@TempDir Path dir) throws Exception {
        MovingClock clock = new MovingClock();
        Path file = dir.resolve("t.json");
        TrackingStore store = TrackingStore.open(file, 90, clock);
        UUID player = UUID.randomUUID();
        store.grant(player, "Steve", "lobby");
        assertTrue(Files.readString(file).contains(player.toString()));
        assertTrue(store.revoke(player));
        assertFalse(store.revoke(player));
        assertFalse(store.consented(player));
        String content = Files.readString(file);
        assertFalse(content.contains(player.toString()));
        assertFalse(content.contains("Steve"));
        // After the withdrawal later events are ignored again.
        store.arrived(player, "Steve", "lobby");
        assertTrue(store.find(player).isEmpty());
    }

    @Test
    void recordsAreDeletedAfterTheRetentionPeriodSinceTheLastVisit(@TempDir Path dir) throws Exception {
        MovingClock clock = new MovingClock();
        Path file = dir.resolve("t.json");
        TrackingStore store = TrackingStore.open(file, 30, clock);
        UUID idle = UUID.randomUUID();
        UUID active = UUID.randomUUID();
        store.grant(idle, "Idle", null);
        store.grant(active, "Active", "lobby");
        clock.advance(Duration.ofDays(29));
        store.left(active);
        clock.advance(Duration.ofDays(2));
        assertEquals(1, store.purge()); // idle: last visit 31 days ago
        assertTrue(store.find(idle).isEmpty());
        assertTrue(store.find(active).isPresent());
        clock.advance(Duration.ofDays(30));
        assertEquals(1, store.purge());
        assertFalse(Files.readString(file).contains("Active"));
    }

    @Test
    void aPlayerWhoIsOnlineIsNeverDue(@TempDir Path dir) throws Exception {
        MovingClock clock = new MovingClock();
        TrackingStore store = TrackingStore.open(dir.resolve("t.json"), 1, clock);
        UUID player = UUID.randomUUID();
        store.grant(player, "Steve", "lobby");
        clock.advance(Duration.ofDays(5));
        assertEquals(0, store.purge());
        assertEquals(5 * 86_400, store.find(player).orElseThrow().playtimeSeconds());
    }

    @Test
    void survivesReopenAndKeepsRunningVisitOutOfTheFile(@TempDir Path dir) throws Exception {
        MovingClock clock = new MovingClock();
        Path file = dir.resolve("t.json");
        TrackingStore store = TrackingStore.open(file, 90, clock);
        UUID player = UUID.randomUUID();
        store.grant(player, "Steve", "lobby");
        clock.advance(Duration.ofSeconds(50));
        store.closeAll();
        store.save();
        TrackedPlayer again = TrackingStore.open(file, 90, clock).find(player).orElseThrow();
        assertEquals(50, again.playtimeSeconds());
        assertEquals("Steve", again.name());
    }

    @Test
    void invalidInputAndDamagedFilesAreRefused(@TempDir Path dir) throws Exception {
        TrackingStore store = TrackingStore.open(dir.resolve("t.json"), 90, new MovingClock());
        assertThrows(IllegalArgumentException.class, () -> store.grant(UUID.randomUUID(), "bad name ", null));
        assertThrows(IllegalArgumentException.class, () -> TrackingStore.open(dir.resolve("u.json"), 0, new MovingClock()));
        assertThrows(TrackingStore.FormatException.class,
                () -> TrackingStore.decode("{\"version\":1}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(TrackingStore.FormatException.class,
                () -> TrackingStore.decode("nope".getBytes(StandardCharsets.UTF_8)));
    }
}
