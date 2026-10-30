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

    private static WebSocket connect(TestApi api, String query, String token, Recorder recorder) throws Exception {
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

    private static void waitForStreams(TestApi api, int count) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (api.server.openEventStreams() != count && System.nanoTime() < end) {
            Thread.sleep(20);
        }
        assertEquals(count, api.server.openEventStreams());
    }

    /** Collects text messages. */
    static final class Recorder implements WebSocket.Listener {

        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
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
