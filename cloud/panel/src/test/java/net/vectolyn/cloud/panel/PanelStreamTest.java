// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.vectolyn.cloud.panel.auth.AuditLog;
import net.vectolyn.cloud.panel.auth.PanelUsers;
import net.vectolyn.cloud.panel.config.PanelConfig;
import net.vectolyn.cloud.panel.config.PanelConfigLoader;
import net.vectolyn.cloud.panel.files.ServiceFiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The live-event stream over a real socket: the checks of the handshake, the delay of an event. */
class PanelStreamTest {

    @TempDir
    Path home;

    private PanelServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    /** Collects text frames. */
    private static final class Listener implements WebSocket.Listener {

        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> closes = new LinkedBlockingQueue<>();
        private final StringBuilder part = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            part.append(data);
            if (last) {
                frames.add(part.toString());
                part.setLength(0);
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            closes.add(status);
            return null;
        }
    }

    @Test
    void eventsArriveWithinASecondAndOnlyOnTheOwnOrigin() throws Exception {
        PanelApiTest.FakeNode node = new PanelApiTest.FakeNode();
        PanelApi api = new PanelApi(PanelUsers.load(home.resolve("secrets").resolve(PanelUsers.FILE_NAME)),
                new AuditLog(home.resolve("logs").resolve(AuditLog.FILE_NAME), Clock.systemUTC()), node, null,
                Clock.systemUTC(), System::nanoTime, new ServiceFiles(home));
        PanelConfigLoader.Settings settings = new PanelConfigLoader.Settings(
                new PanelConfig(1, true, "127.0.0.1", 0, null), Optional.empty());
        server = PanelServer.builder(settings).version("test").api(api).build();
        int port = server.start().getPort();
        String origin = "http://127.0.0.1:" + port;
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> setup = client.send(HttpRequest.newBuilder(URI.create(origin + "/api/panel/setup"))
                .header("Origin", origin).header("Sec-Fetch-Site", "same-origin").header("X-Vectolyn-CSRF", "1")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"vcs_good\",\"password\":\"a long enough phrase\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, setup.statusCode(), setup.body());
        String cookie = setup.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith(PanelApi.SESSION_COOKIE + "=")).findFirst().orElseThrow();
        cookie = cookie.substring(0, cookie.indexOf(';'));
        URI events = URI.create("ws://127.0.0.1:" + port + PanelApi.EVENTS + "?types=service-output&services=lobby-1");

        String session = cookie;
        CompletionException foreign = assertThrows(CompletionException.class, () -> client.newWebSocketBuilder()
                .header("Origin", "https://evil.example").header("Cookie", session)
                .buildAsync(events, new Listener()).join());
        assertEquals(403, ((WebSocketHandshakeException) foreign.getCause()).getResponse().statusCode());
        CompletionException anonymous = assertThrows(CompletionException.class, () -> client.newWebSocketBuilder()
                .header("Origin", origin).buildAsync(events, new Listener()).join());
        assertEquals(401, ((WebSocketHandshakeException) anonymous.getCause()).getResponse().statusCode());

        Listener listener = new Listener();
        WebSocket socket = client.newWebSocketBuilder().header("Origin", origin).header("Cookie", cookie)
                .connectTimeout(Duration.ofSeconds(5)).buildAsync(events, listener).get(5, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (node.streams.isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(1, node.streams.size(), "subscribed after the handshake");
        assertEquals("local:owner", node.streamPrincipal.get().id());
        Channel channel = node.streams.getFirst();

        long worst = 0;
        for (int i = 0; i < 20; i++) {
            long sent = System.nanoTime();
            channel.writeAndFlush(new TextWebSocketFrame("{\"type\":\"service-output\",\"data\":{\"line\":\"" + i + "\"}}"));
            String frame = listener.frames.poll(5, TimeUnit.SECONDS);
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sent);
            worst = Math.max(worst, millis);
            assertTrue(frame != null && frame.contains("\"line\":\"" + i + "\""), String.valueOf(frame));
        }
        System.out.println("[panel-stream] worst delay of 20 events: " + worst + " ms");
        assertTrue(worst < 1000, "an event reaches the browser within a second, took " + worst + " ms");

        HttpResponse<String> logout = client.send(HttpRequest.newBuilder(URI.create(origin + "/api/panel/logout"))
                .header("Origin", origin).header("Sec-Fetch-Site", "same-origin").header("Cookie", cookie)
                .header("X-Vectolyn-CSRF", csrf(client, origin, cookie)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, logout.statusCode(), logout.body());
        api.sweepStreams();
        Integer code = listener.closes.poll(5, TimeUnit.SECONDS);
        assertEquals(1008, code, "signing out ends the stream");
        socket.abort();
    }

    private static String csrf(HttpClient client, String origin, String cookie) throws Exception {
        HttpResponse<String> session = client.send(HttpRequest.newBuilder(URI.create(origin + "/api/panel/session"))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        @SuppressWarnings("unchecked")
        Map<String, Object> json = net.vectolyn.cloud.api.http.ApiJson.mapper().readValue(session.body(), Map.class);
        return (String) json.get("csrf");
    }
}
