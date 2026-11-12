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
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IpBanStoreTest {

    /** Clock that the test moves. */
    private static final class MovingClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void banIsActiveAndSurvivesReopen(@TempDir Path dir) throws Exception {
        MovingClock clock = new MovingClock();
        Path file = dir.resolve("state/ip-bans.json");
        IpBanStore store = IpBanStore.open(file, 90, clock);
        store.ban("203.0.113.7", "login flood", null);
        assertTrue(store.isBanned("203.0.113.7"));
        assertFalse(store.isBanned("203.0.113.8"));
        IpBanStore again = IpBanStore.open(file, 90, clock);
        assertEquals("login flood", again.find("203.0.113.7").orElseThrow().reason());
    }

    @Test
    void entryIsDeletedAfterTheRetentionPeriod(@TempDir Path dir) throws Exception {
        MovingClock clock = new MovingClock();
        Path file = dir.resolve("ip-bans.json");
        IpBanStore store = IpBanStore.open(file, 90, clock);
        store.ban("203.0.113.7", "abuse", null);
        clock.advance(Duration.ofDays(89));
        assertEquals(0, store.purge());
        assertTrue(store.isBanned("203.0.113.7"));
        clock.advance(Duration.ofDays(1));
        // Due entries do not count even before the purge runs.
        assertFalse(store.isBanned("203.0.113.7"));
        assertEquals(1, store.purge());
        assertTrue(store.list().isEmpty());
        assertFalse(Files.readString(file).contains("203.0.113.7"));
    }

    @Test
    void ownExpiryEndsTheBanEarly(@TempDir Path dir) throws Exception {
        MovingClock clock = new MovingClock();
        IpBanStore store = IpBanStore.open(dir.resolve("b.json"), 90, clock);
        store.ban("2001:db8::1", "spam", Duration.ofDays(1));
        assertTrue(store.isBanned("2001:0db8:0:0:0:0:0:1"));
        clock.advance(Duration.ofDays(1));
        assertFalse(store.isBanned("2001:db8::1"));
        assertEquals(1, store.purge());
    }

    @Test
    void banningAgainRenewsTheRetentionPeriod(@TempDir Path dir) throws Exception {
        MovingClock clock = new MovingClock();
        IpBanStore store = IpBanStore.open(dir.resolve("b.json"), 10, clock);
        store.ban("198.51.100.1", "x", null);
        clock.advance(Duration.ofDays(9));
        store.ban("198.51.100.1", "x", null);
        clock.advance(Duration.ofDays(9));
        assertTrue(store.isBanned("198.51.100.1"));
    }

    @Test
    void dueEntriesAreDeletedWhenTheStoreOpens(@TempDir Path dir) throws Exception {
        MovingClock clock = new MovingClock();
        Path file = dir.resolve("b.json");
        IpBanStore.open(file, 5, clock).ban("198.51.100.2", "x", null);
        clock.advance(Duration.ofDays(6));
        IpBanStore reopened = IpBanStore.open(file, 5, clock);
        assertTrue(reopened.list().isEmpty());
        assertFalse(Files.readString(file).contains("198.51.100.2"));
    }

    @Test
    void unbanDeletesTheEntry(@TempDir Path dir) throws Exception {
        IpBanStore store = IpBanStore.open(dir.resolve("b.json"), 90, new MovingClock());
        store.ban("198.51.100.3", "x", null);
        assertTrue(store.unban("198.51.100.3"));
        assertFalse(store.unban("198.51.100.3"));
        assertFalse(Files.readString(dir.resolve("b.json")).contains("198.51.100.3"));
    }

    @Test
    void invalidInputIsRejected(@TempDir Path dir) throws Exception {
        IpBanStore store = IpBanStore.open(dir.resolve("b.json"), 90, new MovingClock());
        assertThrows(IllegalArgumentException.class, () -> store.ban("example.com", "x", null));
        assertThrows(IllegalArgumentException.class, () -> store.ban("256.1.1.1", "x", null));
        assertThrows(IllegalArgumentException.class, () -> store.ban("1.1.1.1", "x".repeat(201), null));
        assertThrows(IllegalArgumentException.class, () -> IpBanStore.open(dir.resolve("c.json"), 0, new MovingClock()));
    }

    @Test
    void damagedFileIsRefused(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("b.json");
        Files.writeString(file, "{\"version\":2,\"bans\":[]}", StandardCharsets.UTF_8);
        assertThrows(IpBanStore.FormatException.class, () -> IpBanStore.open(file, 90, new MovingClock()));
        assertThrows(IpBanStore.FormatException.class, () -> IpBanStore.decode("not json".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IpBanStore.FormatException.class, () -> IpBanStore.decode(
                ("{\"version\":1,\"bans\":[{\"address\":\"1.1.1.1\",\"reason\":\"a\",\"created\":1},"
                        + "{\"address\":\"1.1.1.1\",\"reason\":\"a\",\"created\":1}]}").getBytes(StandardCharsets.UTF_8)));
    }
}
