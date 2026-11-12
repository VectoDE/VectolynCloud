// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.vectolyn.cloud.config.error.ConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TelemetryTest {

    private static final List<ModeStatistics> STATS = List.of(new ModeStatistics("lobby", 3, 120, 2));

    private static PrivacyConfig.Telemetry on() {
        return new PrivacyConfig.Telemetry(true, "https://telemetry.example.org/v1",
                new PrivacyConfig.Consent("Admin One", "2026-11-01T10:00:00Z", 1));
    }

    @Test
    void offByDefaultAndSendsNothing() throws Exception {
        List<byte[]> sent = new ArrayList<>();
        Telemetry telemetry = new Telemetry(PrivacyConfig.defaults().telemetry(), "0.1.0", "Linux", () -> STATS, sent::add);
        assertFalse(PrivacyConfig.defaults().telemetry().enabled());
        assertFalse(telemetry.active());
        assertTrue(telemetry.report().isEmpty());
        assertFalse(telemetry.send());
        assertTrue(sent.isEmpty());
    }

    @Test
    void enabledWithoutConsentIsNotActive() throws Exception {
        List<byte[]> sent = new ArrayList<>();
        Telemetry telemetry = new Telemetry(new PrivacyConfig.Telemetry(true, "https://t.example.org/", null),
                "0.1.0", "Linux", () -> STATS, sent::add);
        assertFalse(telemetry.active());
        assertFalse(telemetry.send());
        assertTrue(sent.isEmpty());
    }

    @Test
    void reportHoldsOnlyVersionOsAndTotals() throws Exception {
        List<byte[]> sent = new ArrayList<>();
        Telemetry telemetry = new Telemetry(on(), "0.1.0", "Linux", () -> STATS, sent::add);
        assertTrue(telemetry.send());
        assertEquals("{\"report-version\":1,\"cloud-version\":\"0.1.0\",\"os\":\"Linux\",\"modes\":"
                + "[{\"mode\":\"lobby\",\"logins\":3,\"playtime-seconds\":120,\"peak-players\":2}]}",
                new String(sent.get(0), StandardCharsets.UTF_8));
    }

    @Test
    void configRefusesEnabledTelemetryWithoutConsentOrWithAPlainHttpEndpoint() {
        assertThrows(ConfigException.class, () -> PrivacyConfigLoader.parse(
                "schema-version: 1\ntelemetry:\n  enabled: true\n  endpoint: https://t.example.org/\n"
                        .getBytes(StandardCharsets.UTF_8)));
        String consent = "  consent:\n    given-by: Admin One\n    given-at: 2026-11-01T10:00:00Z\n    text-version: 1\n";
        assertThrows(ConfigException.class, () -> PrivacyConfigLoader.parse(
                ("schema-version: 1\ntelemetry:\n  enabled: true\n  endpoint: http://t.example.org/\n" + consent)
                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(ConfigException.class, () -> PrivacyConfigLoader.parse(
                ("schema-version: 1\ntelemetry:\n  enabled: true\n  endpoint: https://t.example.org/\n"
                        + consent.replace("text-version: 1", "text-version: 7")).getBytes(StandardCharsets.UTF_8)));
        assertThrows(ConfigException.class, () -> PrivacyConfigLoader.parse(
                ("schema-version: 1\ntelemetry:\n  enabled: true\n  endpoint: https://t.example.org/\n"
                        + consent.replace("2026-11-01", "2999-11-12")).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void configAcceptsEnabledTelemetryWithConsentAndRevokedConsentStaysOff() throws Exception {
        String consent = "  consent:\n    given-by: Admin One\n    given-at: 2026-11-01T10:00:00Z\n    text-version: 1\n";
        PrivacyConfig ok = PrivacyConfigLoader.parse(
                ("schema-version: 1\ntelemetry:\n  enabled: true\n  endpoint: https://t.example.org/\n" + consent)
                        .getBytes(StandardCharsets.UTF_8));
        assertTrue(ok.telemetry().enabled());
        PrivacyConfig off = PrivacyConfigLoader.parse(
                ("schema-version: 1\ntelemetry:\n  enabled: false\n" + consent).getBytes(StandardCharsets.UTF_8));
        assertFalse(off.telemetry().enabled());
    }

    @Test
    void httpSenderPostsJsonAndFailsOnErrorStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> type = new AtomicReference<>();
        server.createContext("/ok", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            type.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/fail", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            new HttpTelemetrySender(URI.create(base + "/ok"), HttpClient.newHttpClient())
                    .post("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
            assertEquals("{\"a\":1}", body.get());
            assertEquals("application/json", type.get());
            assertThrows(IOException.class, () -> new HttpTelemetrySender(URI.create(base + "/fail"),
                    HttpClient.newHttpClient()).post(new byte[0]));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void runtimeDefaultsToStatisticsOnTelemetryOff(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve("config"));
        try (PrivacyRuntime runtime = PrivacyRuntime.open(home, Clock.systemUTC(), "0.1.0")) {
            assertFalse(runtime.telemetry().active());
            java.util.UUID player = java.util.UUID.randomUUID();
            runtime.playerArrived(player, "Steve", "lobby");
            runtime.playerLeft(player);
            assertEquals(1, runtime.statistics().snapshot().get(0).logins());
        }
        assertTrue(Files.exists(home.resolve("state/statistics.json")));
    }

    @Test
    void runtimeCollectsNothingWhenStatisticsAreOff(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/privacy.yml"), "schema-version: 1\nstatistics:\n  enabled: false\n");
        try (PrivacyRuntime runtime = PrivacyRuntime.open(home, Clock.systemUTC(), "0.1.0")) {
            runtime.playerArrived(java.util.UUID.randomUUID(), "Steve", "lobby");
            assertTrue(runtime.statistics().snapshot().isEmpty());
        }
        assertFalse(Files.exists(home.resolve("state/statistics.json")));
    }
}
