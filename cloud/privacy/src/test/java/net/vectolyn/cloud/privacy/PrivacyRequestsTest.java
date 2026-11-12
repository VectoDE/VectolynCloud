// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import net.vectolyn.cloud.config.error.ConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivacyRequestsTest {

    private static final PrivacyConfig.Tracking ON = new PrivacyConfig.Tracking(true, 60, "Example Network, privacy@example.org");

    private static String ask(PrivacyRequests requests, UUID player, String request) {
        return new String(requests.handle(request.getBytes(StandardCharsets.UTF_8), player, "Steve", "lobby"),
                StandardCharsets.UTF_8);
    }

    @Test
    void consentAccessAndWithdrawalWork(@TempDir Path dir) throws Exception {
        TrackingStore store = TrackingStore.open(dir.resolve("t.json"), 60, Clock.systemUTC());
        PrivacyRequests requests = new PrivacyRequests(store, ON);
        UUID player = UUID.randomUUID();
        assertEquals("ok\nnot-consented", ask(requests, player, "status"));
        assertEquals("ok\nnot-consented", ask(requests, player, "data"));
        assertEquals("ok", ask(requests, player, "grant"));
        assertEquals("ok\nconsented", ask(requests, player, "status"));
        String data = ask(requests, player, "data");
        assertTrue(data.startsWith("ok\n{"));
        assertTrue(data.contains(player.toString()));
        assertTrue(data.contains("\"modes\""));
        assertEquals("ok", ask(requests, player, "revoke"));
        assertEquals("ok\nnot-consented", ask(requests, player, "status"));
    }

    @Test
    void noticeNamesControllerPurposeAndRetentionInBothLanguages(@TempDir Path dir) throws Exception {
        PrivacyRequests requests = new PrivacyRequests(TrackingStore.open(dir.resolve("t.json"), 60, Clock.systemUTC()), ON);
        String en = ask(requests, UUID.randomUUID(), "notice en");
        assertTrue(en.contains("Example Network, privacy@example.org"));
        assertTrue(en.contains("Purpose:"));
        assertTrue(en.contains("60 days"));
        String de = ask(requests, UUID.randomUUID(), "notice de");
        assertTrue(de.contains("Zweck:"));
        assertTrue(de.contains("60 Tage"));
    }

    @Test
    void disabledTrackingRefusesEverythingAndBadRequestsAreRejected(@TempDir Path dir) throws Exception {
        TrackingStore store = TrackingStore.open(dir.resolve("t.json"), 60, Clock.systemUTC());
        PrivacyRequests off = new PrivacyRequests(store, PrivacyConfig.Tracking.defaults());
        assertEquals("error disabled", ask(off, UUID.randomUUID(), "grant"));
        assertEquals(0, store.size());
        PrivacyRequests on = new PrivacyRequests(store, ON);
        UUID player = UUID.randomUUID();
        assertEquals("error bad-request", ask(on, player, "dance"));
        assertEquals("error bad-request", ask(on, player, "notice fr"));
        assertEquals("error bad-request", ask(on, player, ""));
        assertEquals("error bad-request", ask(on, player, "x".repeat(40)));
    }

    @Test
    void configNeedsAControllerWhenTrackingIsOn() throws Exception {
        assertThrows(ConfigException.class, () -> PrivacyConfigLoader.parse(
                "schema-version: 1\ntracking:\n  enabled: true\n".getBytes(StandardCharsets.UTF_8)));
        assertThrows(ConfigException.class, () -> PrivacyConfigLoader.parse(
                "schema-version: 1\ntracking:\n  retention-days: 0\n".getBytes(StandardCharsets.UTF_8)));
        PrivacyConfig ok = PrivacyConfigLoader.parse(
                "schema-version: 1\ntracking:\n  enabled: true\n  controller: Example, a@example.org\n"
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals(90, ok.tracking().retentionDays());
        assertEquals(false, PrivacyConfig.defaults().tracking().enabled());
    }

    @Test
    void runtimeTracksOnlyConsentedPlayers(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/privacy.yml"),
                "schema-version: 1\ntracking:\n  enabled: true\n  controller: Example, a@example.org\n");
        UUID agreed = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (PrivacyRuntime runtime = PrivacyRuntime.open(home, Clock.systemUTC(), "0.1.0")) {
            runtime.tracking().grant(agreed, "Agreed", null);
            runtime.playerArrived(agreed, "Agreed", "lobby");
            runtime.playerArrived(other, "Other", "lobby");
            runtime.playerLeft(agreed);
            runtime.playerLeft(other);
            assertEquals(1, runtime.tracking().size());
            assertEquals(1, runtime.tracking().find(agreed).orElseThrow().logins());
            // The totals per mode still count both players, without who they are.
            assertEquals(2, runtime.statistics().snapshot().get(0).logins());
        }
        assertTrue(Files.readString(home.resolve("state/tracking.json")).contains("Agreed"));
        assertEquals(false, Files.readString(home.resolve("state/statistics.json")).contains("Agreed"));
    }
}
