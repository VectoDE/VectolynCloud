package net.vectora.cloud.monitor.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.cloud.monitor.alert.Alert;
import net.vectora.cloud.monitor.alert.AlertEvent;
import net.vectora.cloud.monitor.alert.RuleParser;
import net.vectora.cloud.monitor.alert.Severity;
import net.vectora.cloud.monitor.config.MonitorConfig.WebhookFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The webhook against real HTTPS servers on loopback with a self-signed certificate. */
class WebhookNotifierTest {

    /** Stands in for the secret part of a Discord webhook URL; it must never show up in a message. */
    private static final String TOKEN = "Zx9tokenSECRETpart42";

    @TempDir
    static Path dir;

    private static TestTls tls;
    private final List<HttpServer> servers = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void certificate() throws Exception {
        tls = TestTls.create(dir);
    }

    @AfterEach
    void stopServers() {
        servers.forEach(s -> s.stop(0));
    }

    private interface Handler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }

    private HttpsServer https(Handler handler) throws IOException {
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls.server));
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        servers.add(server);
        return server;
    }

    private static WebhookUrl url(HttpServer server) {
        return WebhookUrl.of(Secret.of("https://127.0.0.1:" + server.getAddress().getPort() + "/api/webhooks/1/" + TOKEN));
    }

    private static AlertEvent fired() {
        Alert alert = new Alert(3, "low-tps", "lobby-1", Severity.WARNING, RuleParser.parse("tps < 18 for 60s"), 12.4,
                Instant.parse("2026-10-29T12:00:00Z"), Optional.empty(), Optional.empty(), Optional.empty());
        return new AlertEvent.Fired(alert);
    }

    private static WebhookNotifier notifier(WebhookUrl url, WebhookFormat format, Duration timeout) {
        return new WebhookNotifier(url, format, timeout, "VectoraCloud node-1", tls.client);
    }

    private static void assertNoSecret(String message) {
        assertFalse(message.contains(TOKEN), message);
        assertFalse(message.contains("127.0.0.1"), message);
        assertFalse(message.contains("/api/webhooks"), message);
    }

    @Test
    void postsDiscordJsonWithoutMentions() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        List<String> types = new CopyOnWriteArrayList<>();
        HttpsServer server = https(exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            types.add(exchange.getRequestHeaders().getFirst("Content-Type") + " " + exchange.getRequestMethod());
            exchange.sendResponseHeaders(204, -1);
        });
        try (WebhookNotifier notifier = notifier(url(server), WebhookFormat.DISCORD, Duration.ofSeconds(5))) {
            notifier.send(fired());
        }
        assertEquals(List.of("application/json POST"), types);
        JsonNode body = JsonMapper.builder().build().readTree(bodies.get(0));
        String content = body.get("content").asString();
        assertTrue(content.startsWith("[WARNING] Alert #3 low-tps on lobby-1: tps 12.4"), content);
        assertTrue(content.endsWith("VectoraCloud node-1"), content);
        assertTrue(body.get("allowed_mentions").get("parse").isEmpty(), "no @everyone, no role pings");
    }

    @Test
    void slackAndJsonFormats() throws Exception {
        WebhookUrl url = WebhookUrl.of(Secret.of("https://hooks.example.org/x"));
        JsonMapper json = JsonMapper.builder().build();
        try (WebhookNotifier slack = notifier(url, WebhookFormat.SLACK, Duration.ofSeconds(1));
             WebhookNotifier plain = notifier(url, WebhookFormat.JSON, Duration.ofSeconds(1))) {
            assertTrue(json.readTree(slack.body(fired())).get("text").asString().contains("low-tps"));
            JsonNode full = json.readTree(plain.body(fired()));
            assertEquals("fired", full.get("event").asString());
            assertEquals(3, full.get("id").asInt());
            assertEquals("tps", full.get("metric").asString());
            assertEquals(12.4, full.get("value").asDouble());
            assertEquals("tps < 18 for 1m", full.get("condition").asString());
            assertEquals("2026-10-29T12:00:00Z", full.get("fired-at").asString());
        }
    }

    @Test
    void redirectsAreNotFollowed() throws Exception {
        AtomicInteger stolen = new AtomicInteger();
        HttpsServer elsewhere = https(exchange -> {
            stolen.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
        });
        HttpsServer redirecting = https(exchange -> {
            exchange.getResponseHeaders().set("Location",
                    "https://localhost:" + elsewhere.getAddress().getPort() + "/collect");
            exchange.sendResponseHeaders(307, -1);
        });
        try (WebhookNotifier notifier = notifier(url(redirecting), WebhookFormat.DISCORD, Duration.ofSeconds(5))) {
            IOException e = assertThrows(IOException.class, () -> notifier.send(fired()));
            assertTrue(e.getMessage().contains("redirect (HTTP 307)"), e.getMessage());
            assertNoSecret(e.getMessage());
        }
        assertEquals(0, stolen.get(), "the post must not reach the other host");
    }

    @Test
    void slowServersHitTheTimeout() throws Exception {
        HttpsServer slow = https(exchange -> {
            try {
                Thread.sleep(8_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(204, -1);
        });
        long start = System.nanoTime();
        try (WebhookNotifier notifier = notifier(url(slow), WebhookFormat.DISCORD, Duration.ofSeconds(1))) {
            IOException e = assertThrows(IOException.class, () -> notifier.send(fired()));
            assertTrue(e.getMessage().contains("within 1 s"), e.getMessage());
            assertNoSecret(e.getMessage());
        }
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5, "gives up after about the timeout");
    }

    @Test
    void errorStatusesFail() throws Exception {
        HttpsServer failing = https(exchange -> exchange.sendResponseHeaders(500, -1));
        try (WebhookNotifier notifier = notifier(url(failing), WebhookFormat.DISCORD, Duration.ofSeconds(5))) {
            IOException e = assertThrows(IOException.class, () -> notifier.send(fired()));
            assertEquals("webhook: answered with HTTP 500", e.getMessage());
        }
    }

    @Test
    void untrustedCertificatesFailWithoutNamingTheUrl() throws Exception {
        HttpsServer server = https(exchange -> exchange.sendResponseHeaders(204, -1));
        // The JDK's own trust store does not know the self-signed certificate.
        try (WebhookNotifier notifier = new WebhookNotifier(url(server), WebhookFormat.DISCORD, Duration.ofSeconds(5),
                "node-1")) {
            IOException e = assertThrows(IOException.class, () -> notifier.send(fired()));
            assertNoSecret(e.getMessage());
            assertTrue(e.getMessage().startsWith("webhook: "), e.getMessage());
        }
    }

    @Test
    void refusedConnectionsFailWithoutNamingTheUrl() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = free.getLocalPort();
        }
        WebhookUrl url = WebhookUrl.of(Secret.of("https://127.0.0.1:" + port + "/api/webhooks/1/" + TOKEN));
        try (WebhookNotifier notifier = notifier(url, WebhookFormat.DISCORD, Duration.ofSeconds(3))) {
            IOException e = assertThrows(IOException.class, () -> notifier.send(fired()));
            assertNoSecret(e.getMessage());
        }
    }

    @Test
    void toStringHidesTheUrl() throws Exception {
        WebhookUrl url = WebhookUrl.of(Secret.of("https://discord.com/api/webhooks/1/" + TOKEN));
        assertEquals("***", url.toString());
        try (WebhookNotifier notifier = new WebhookNotifier(url, WebhookFormat.DISCORD, Duration.ofSeconds(1), "n")) {
            assertNoSecret(notifier.toString());
            assertNoSecret(notifier.name());
        }
    }
}
