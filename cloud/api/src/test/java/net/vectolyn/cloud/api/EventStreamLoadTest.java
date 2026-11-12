// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.vectolyn.cloud.api.event.ApiEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Load test of the event stream connections: many clients connect at once, more than the server
 * allows, and a burst of events is fanned out to all of them from several threads.
 *
 * <p>Checks: exactly the allowed number of streams opens, the rest is refused with 503 and the
 * server keeps answering; every stream accounts for every event (received, or counted in a
 * {@code dropped} event); the streams close cleanly. The figures go to standard output (kept in
 * the JUnit XML of every CI run) and to {@code build/reports/api-load/event-streams.md}.
 */
@Timeout(300)
class EventStreamLoadTest {

    private static final int STREAMS = 200;
    private static final int ATTEMPTS = 260;
    private static final int WAVE = 40;
    private static final int PUBLISHERS = 4;
    private static final int EVENTS_PER_PUBLISHER = 2_500;
    private static final Pattern DROPPED = Pattern.compile("\"count\":(\\d+)");

    @TempDir
    Path home;

    @Test
    void manyStreamsConnectAtOnceAndEveryEventIsAccountedFor() throws Exception {
        ApiLimits limits = new ApiLimits(256 * 1024, 1_000_000, 1_000_000, 1_000_000, 1_000_000, 10, 64,
                ATTEMPTS + 64, ATTEMPTS + 64, STREAMS, 120);
        try (TestApi api = new TestApi(home, s -> new ApiSettings(s.bind(), s.port(), s.tls(), s.corsOrigins(), limits))) {
            URI uri = URI.create("ws://127.0.0.1:" + api.address.getPort() + "/api/v1/events?types=service-output");

            // 1. Connection storm: waves of WAVE handshakes in flight at once (the listen backlog of
            // the server is 64, more at the same instant would test the operating system instead).
            long connectStart = System.nanoTime();
            List<WebSocket> open = new ArrayList<>();
            List<Counter> openCounters = new ArrayList<>();
            int refused = 0;
            for (int from = 0; from < ATTEMPTS; from += WAVE) {
                List<Counter> counters = new ArrayList<>();
                List<CompletableFuture<WebSocket>> attempts = new ArrayList<>();
                for (int i = from; i < Math.min(ATTEMPTS, from + WAVE); i++) {
                    Counter counter = new Counter();
                    counters.add(counter);
                    attempts.add(api.http.newWebSocketBuilder().header("Authorization", "Bearer " + api.admin)
                            .buildAsync(uri, counter));
                }
                for (int i = 0; i < attempts.size(); i++) {
                    try {
                        open.add(attempts.get(i).get(60, TimeUnit.SECONDS));
                        openCounters.add(counters.get(i));
                    } catch (ExecutionException e) {
                        assertTrue(e.getCause() instanceof WebSocketHandshakeException, e.toString());
                        assertEquals(503, ((WebSocketHandshakeException) e.getCause()).getResponse().statusCode());
                        refused++;
                    }
                }
            }
            double connectSeconds = (System.nanoTime() - connectStart) / 1e9;
            assertEquals(STREAMS, open.size(), "exactly the allowed number of streams opens");
            assertEquals(ATTEMPTS - STREAMS, refused);
            assertEquals(STREAMS, api.server.openEventStreams());
            HttpResponse<String> during = api.get("/api/v1/node", api.admin);
            assertEquals(200, during.statusCode(), "the REST API keeps answering with every stream open");

            // 2. Fan-out burst from several threads at once.
            int total = PUBLISHERS * EVENTS_PER_PUBLISHER;
            long publishStart = System.nanoTime();
            try (ExecutorService pool = Executors.newFixedThreadPool(PUBLISHERS)) {
                for (int p = 0; p < PUBLISHERS; p++) {
                    int publisher = p;
                    pool.execute(() -> {
                        for (int n = 0; n < EVENTS_PER_PUBLISHER; n++) {
                            api.server.publish(new ApiEvent.ServiceOutput("lobby-1", "p" + publisher + " line " + n));
                        }
                    });
                }
            }
            double publishSeconds = (System.nanoTime() - publishStart) / 1e9;
            // A marker after the burst: the "dropped" count for a stream is sent before its next event.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            int markers = 0;
            while (openCounters.stream().anyMatch(c -> !c.sawEnd) && System.nanoTime() < deadline) {
                api.server.publish(new ApiEvent.ServiceOutput("lobby-1", "end-of-burst"));
                markers++;
                Thread.sleep(250);
            }
            double deliverSeconds = (System.nanoTime() - publishStart) / 1e9;

            long received = 0;
            long dropped = 0;
            for (Counter c : openCounters) {
                assertTrue(c.sawEnd, "every stream gets the end marker");
                long lines = c.lines.get();
                long lost = c.dropped.get();
                assertTrue(lines + lost >= total, "every event is received or counted as dropped: " + lines + "+" + lost);
                assertTrue(lines + lost <= total + markers, "nothing is invented: " + lines + "+" + lost);
                received += lines;
                dropped += lost;
            }

            // 3. Everything closes cleanly.
            for (WebSocket socket : open) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            }
            EventStreamTest.waitForStreams(api, 0);
            assertEquals(200, api.get("/api/v1/node", api.admin).statusCode());

            String report = String.format(Locale.ROOT, """
                    # Event stream load test

                    | Figure | Value |
                    |---|---|
                    | Connection attempts (40 in flight at once) | %d |
                    | Streams opened (limit) | %d |
                    | Refused with 503 | %d |
                    | Time to settle every attempt | %.2f s |
                    | Events published (%d threads) | %d |
                    | Publish time | %.2f s (%.0f events/s) |
                    | Deliveries (events x streams) | %d |
                    | Delivered | %d (%.1f %%) |
                    | Dropped for slow sockets, reported | %d |
                    | Time until every stream had the burst | %.2f s (%.0f deliveries/s) |
                    """, ATTEMPTS, STREAMS, refused, connectSeconds, PUBLISHERS, total, publishSeconds,
                    total / publishSeconds, (long) total * STREAMS, received, 100.0 * received / ((double) total * STREAMS),
                    dropped, deliverSeconds, received / deliverSeconds);
            System.out.println(report);
            writeReport(report);
        }
    }

    private static void writeReport(String report) throws IOException {
        Path dir = Path.of("build", "reports", "api-load");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("event-streams.md"), report, StandardCharsets.UTF_8);
    }

    /** Counts instead of keeping messages, so 200 clients with 10,000 events stay small. */
    static final class Counter implements WebSocket.Listener {

        final AtomicLong lines = new AtomicLong();
        final AtomicLong dropped = new AtomicLong();
        volatile boolean sawEnd;
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(Long.MAX_VALUE);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String message = partial.toString();
                partial.setLength(0);
                if (message.startsWith("{\"type\":\"dropped\"")) {
                    Matcher m = DROPPED.matcher(message);
                    if (m.find()) {
                        dropped.addAndGet(Long.parseLong(m.group(1)));
                    }
                } else if (message.contains("end-of-burst")) {
                    sawEnd = true;
                } else if (message.startsWith("{\"type\":\"service-output\"")) {
                    lines.incrementAndGet();
                }
            }
            return null;
        }
    }
}
