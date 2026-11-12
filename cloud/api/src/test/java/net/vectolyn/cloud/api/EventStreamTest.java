// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.vectolyn.cloud.api.auth.Scope;
import net.vectolyn.cloud.api.event.ApiEvent;
import net.vectolyn.cloud.config.secret.RedactingFilter;
import net.vectolyn.cloud.config.secret.Secret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(120)
class EventStreamTest {

    @TempDir
    Path home;

    @Test
    void streamsEventsFilteredAndRedacted() throws Exception {
        try (TestApi api = new TestApi(home)) {
            api.backend.filter = RedactingFilter.of(List.of(Secret.of("hunter2-very-secret")));
            Recorder all = new Recorder();
            WebSocket socket = connect(api, "", api.admin, all);
            Recorder outputOnly = new Recorder();
            connect(api, "?types=service-output&services=lobby-1", api.admin, outputOnly);
            waitForStreams(api, 2);

            api.server.publish(new ApiEvent.ServiceState(FakeBackend.view("lobby-1", "running"), "starting"));
            api.server.publish(new ApiEvent.ServiceOutput("lobby-1", "password is hunter2-very-secret"));
            api.server.publish(new ApiEvent.ServiceOutput("lobby-2", "other service"));
            api.server.publish(new ApiEvent.Player("join", "00000000-0000-0000-0000-000000000001", "Steve", "lobby-1"));

            String state = all.next();
            assertTrue(state.startsWith("{\"type\":\"service-state\""), state);
            assertTrue(state.contains("\"previous\":\"starting\"") && state.contains("\"id\":\"lobby-1\""), state);
            String output = all.next();
            assertTrue(output.contains("\"line\":\"password is ***\""), output);
            assertFalse(output.contains("hunter2"));
            assertTrue(all.next().contains("lobby-2"));
            String player = all.next();
            assertTrue(player.contains("\"type\":\"player\"") && player.contains("\"player-name\":\"Steve\""), player);

            String filtered = outputOnly.next();
            assertTrue(filtered.contains("password is ***"), filtered);
            assertNull(outputOnly.messages.poll(500, TimeUnit.MILLISECONDS), "the filter drops the rest");
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        }
    }

    @Test
    void streamsNeedATokenWithScopeAndEndWhenItIsRevoked() throws Exception {
        try (TestApi api = new TestApi(home)) {
            assertHandshakeFails(api, null);
            assertHandshakeFails(api, "vct_" + "C".repeat(43));
            String config = api.token("config", Scope.CONFIG_READ);
            assertHandshakeFails(api, config);
            assertEquals(0, api.server.openEventStreams());

            String reader = api.token("reader", Scope.SERVICES_READ);
            Recorder recorder = new Recorder();
            connect(api, "", reader, recorder);
            waitForStreams(api, 1);
            api.tokens.revoke("reader");
            assertEquals(1008, recorder.closed.get(10, TimeUnit.SECONDS));
            waitForStreams(api, 0);
        }
    }

    @Test
    void aStreamEndsWhenItsTokenExpires() throws Exception {
        try (TestApi api = new TestApi(home)) {
            String reader = api.tokens.create("expiring", java.util.Set.of(Scope.SERVICES_READ),
                    java.time.Duration.ofHours(1)).token().reveal();
            Recorder recorder = new Recorder();
            connect(api, "", reader, recorder);
            waitForStreams(api, 1);
            assertNull(recorder.closed.getNow(null), "open while the token is valid");
            api.now.set(api.now.get().plus(java.time.Duration.ofHours(1)));
            assertEquals(1008, recorder.closed.get(10, TimeUnit.SECONDS));
            waitForStreams(api, 0);
            assertHandshakeFails(api, reader);
        }
    }

    @Test
    void eachEventTypeNeedsItsScope() throws Exception {
        try (TestApi api = new TestApi(home)) {
            String services = api.token("services", Scope.SERVICES_READ);
            String node = api.token("node", Scope.NODE_READ);
            String config = api.token("config", Scope.CONFIG_READ);
            assertHandshakeStatus(api, "?types=metrics", services, 403);
            assertHandshakeStatus(api, "?types=node-log", node, 403);
            assertHandshakeStatus(api, "?types=service-state", node, 403);
            assertHandshakeStatus(api, "", config, 403);
            assertEquals(0, api.server.openEventStreams());

            // Without types a stream gets exactly what its scopes allow.
            Recorder nodeOnly = new Recorder(true);
            connect(api, "", node, nodeOnly);
            Recorder servicesOnly = new Recorder();
            connect(api, "", services, servicesOnly);
            waitForStreams(api, 2);
            api.server.publish(new ApiEvent.ServiceOutput("lobby-1", "only for services:read"));
            api.server.publish(new ApiEvent.NodeLog("info", "net.vectolyn.cloud.node.CloudNode", "only for admin"));
            String metrics = nodeOnly.next();
            assertTrue(metrics.startsWith("{\"type\":\"metrics\""), metrics);
            assertTrue(metrics.contains("\"node-id\":\"node-1\"") && metrics.contains("\"heap-used-bytes\""), metrics);
            assertTrue(servicesOnly.next().contains("only for services:read"));
            for (String message : nodeOnly.messages) {
                assertTrue(message.startsWith("{\"type\":\"metrics\""), message);
            }
            assertNull(servicesOnly.messages.poll(600, TimeUnit.MILLISECONDS), "no metrics, no node log");
        }
    }

    @Test
    void theNodeLogReachesAdminStreamsRedacted() throws Exception {
        try (TestApi api = new TestApi(home)) {
            api.backend.filter = RedactingFilter.of(List.of(Secret.of("hunter2-very-secret")));
            Recorder admin = new Recorder();
            connect(api, "?types=node-log", api.admin, admin);
            waitForStreams(api, 1);
            java.util.logging.Logger logger = java.util.logging.Logger.getLogger("net.vectolyn.cloud.node.Test");
            java.util.logging.LogRecord record = new java.util.logging.LogRecord(java.util.logging.Level.WARNING,
                    "Password {0} rejected\nsecond line");
            record.setLoggerName(logger.getName());
            record.setParameters(new Object[] {"hunter2-very-secret"});
            record.setThrown(new IllegalStateException("stack trace text"));
            api.server.logHandler().publish(record);
            java.util.logging.LogRecord fine = new java.util.logging.LogRecord(java.util.logging.Level.FINE, "debug");
            fine.setLoggerName(logger.getName());
            api.server.logHandler().publish(fine);
            java.util.logging.LogRecord own = new java.util.logging.LogRecord(java.util.logging.Level.INFO, "loop");
            own.setLoggerName("net.vectolyn.cloud.api.ApiServer");
            api.server.logHandler().publish(own);

            String line = admin.next();
            assertTrue(line.contains("\"type\":\"node-log\"") && line.contains("\"level\":\"warning\""), line);
            assertTrue(line.contains("Password *** rejected second line (IllegalStateException)"), line);
            assertFalse(line.contains("hunter2") || line.contains("stack trace text"), line);
            assertNull(admin.messages.poll(500, TimeUnit.MILLISECONDS), "FINE and the API's own records are skipped");
        }
    }

    @Test
    void browsersPassTheTokenAsSubprotocol() throws Exception {
        try (TestApi api = new TestApi(home)) {
            Recorder recorder = new Recorder();
            WebSocket socket = api.http.newWebSocketBuilder()
                    .subprotocols("vectolyn.v1", "vectolyn.bearer." + api.admin)
                    .buildAsync(URI.create("ws://127.0.0.1:" + api.address.getPort() + "/api/v1/events"), recorder)
                    .get(10, TimeUnit.SECONDS);
            assertEquals("vectolyn.v1", socket.getSubprotocol(), "the token is never echoed");
            waitForStreams(api, 1);
            api.server.publish(new ApiEvent.ServiceOutput("lobby-1", "hello"));
            assertTrue(recorder.next().contains("hello"));
        }
    }

    @Test
    void aBearerSubprotocolWithoutVectolynV1IsRefused() throws Exception {
        try (TestApi api = new TestApi(home)) {
            // Without a protocol the server can pick, Netty would log the offered list with the token in it.
            CompletableFuture<WebSocket> future = api.http.newWebSocketBuilder()
                    .subprotocols("vectolyn.bearer." + api.admin)
                    .buildAsync(URI.create("ws://127.0.0.1:" + api.address.getPort() + "/api/v1/events"), new Recorder());
            CompletionException e = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class, future::join);
            assertInstanceOf(WebSocketHandshakeException.class, e.getCause());
            assertEquals(400, ((WebSocketHandshakeException) e.getCause()).getResponse().statusCode());
            assertEquals(0, api.server.openEventStreams());
        }
    }

    @Test
    void unknownFilterValuesAreRefused() throws Exception {
        try (TestApi api = new TestApi(home)) {
            CompletableFuture<WebSocket> future = api.http.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + api.admin)
                    .buildAsync(URI.create("ws://127.0.0.1:" + api.address.getPort() + "/api/v1/events?types=secrets"),
                            new Recorder());
            CompletionException e = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class, future::join);
            assertInstanceOf(WebSocketHandshakeException.class, e.getCause());
            assertEquals(400, ((WebSocketHandshakeException) e.getCause()).getResponse().statusCode());
        }
    }

    @Test
    void aForeignOriginCannotOpenAStream() throws Exception {
        try (TestApi api = new TestApi(home)) {
            CompletableFuture<WebSocket> future = api.http.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + api.admin)
                    .header("Origin", "https://evil.example.org")
                    .buildAsync(URI.create("ws://127.0.0.1:" + api.address.getPort() + "/api/v1/events"), new Recorder());
            CompletionException e = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class, future::join);
            assertInstanceOf(WebSocketHandshakeException.class, e.getCause());
            assertEquals(403, ((WebSocketHandshakeException) e.getCause()).getResponse().statusCode());
            assertEquals(0, api.server.openEventStreams());
        }
    }

    static WebSocket connect(TestApi api, String query, String token, Recorder recorder) throws Exception {
        return api.http.newWebSocketBuilder().header("Authorization", "Bearer " + token)
                .buildAsync(URI.create("ws://127.0.0.1:" + api.address.getPort() + "/api/v1/events" + query), recorder)
                .get(10, TimeUnit.SECONDS);
    }

    private static void assertHandshakeFails(TestApi api, String token) {
        WebSocket.Builder builder = api.http.newWebSocketBuilder();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        CompletableFuture<WebSocket> future = builder.buildAsync(
                URI.create("ws://127.0.0.1:" + api.address.getPort() + "/api/v1/events"), new Recorder());
        CompletionException e = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class, future::join);
        assertInstanceOf(WebSocketHandshakeException.class, e.getCause());
        int status = ((WebSocketHandshakeException) e.getCause()).getResponse().statusCode();
        assertTrue(status == 401 || status == 403, "status " + status);
    }

    private static void assertHandshakeStatus(TestApi api, String query, String token, int expected) {
        CompletableFuture<WebSocket> future = api.http.newWebSocketBuilder().header("Authorization", "Bearer " + token)
                .buildAsync(URI.create("ws://127.0.0.1:" + api.address.getPort() + "/api/v1/events" + query),
                        new Recorder());
        CompletionException e = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class, future::join);
        assertInstanceOf(WebSocketHandshakeException.class, e.getCause());
        assertEquals(expected, ((WebSocketHandshakeException) e.getCause()).getResponse().statusCode(), query);
    }

    static void waitForStreams(TestApi api, int count) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (api.server.openEventStreams() != count && System.nanoTime() < end) {
            Thread.sleep(20);
        }
        assertEquals(count, api.server.openEventStreams());
    }

    /** Collects text messages; {@link #next()} skips the periodic metrics unless asked to keep them. */
    static final class Recorder implements WebSocket.Listener {

        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final boolean keepMetrics;

        Recorder() {
            this(false);
        }

        Recorder(boolean keepMetrics) {
            this.keepMetrics = keepMetrics;
        }
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String message = partial.toString();
                partial.setLength(0);
                if (keepMetrics || !message.startsWith("{\"type\":\"metrics\"")) {
                    messages.add(message);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        String next() throws InterruptedException {
            String message = messages.poll(10, TimeUnit.SECONDS);
            if (message == null) {
                throw new AssertionError("no event within 10 s");
            }
            return message;
        }
    }
}
