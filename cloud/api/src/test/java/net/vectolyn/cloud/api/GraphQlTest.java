// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import net.vectolyn.cloud.api.auth.Scope;
import net.vectolyn.cloud.api.event.ApiEvent;
import net.vectolyn.cloud.api.http.ApiJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code /api/v1/graphql}: queries and mutations run the REST routes with the caller's token,
 * the limits refuse expensive documents, introspection follows the setting, and subscriptions
 * work over {@code graphql-transport-ws}.
 */
@Timeout(120)
class GraphQlTest {

    @TempDir
    Path home;

    @Test
    void aQueryReadsSeveralAreasAtOnce() throws Exception {
        try (TestApi api = new TestApi(home)) {
            Map<String, Object> result = graphQl(api, api.admin,
                    "{ services { totalCount next items { id state startedAt pid } } nodeStatus { nodeId heapMaxBytes }"
                            + " config { etag document } self { name scopes expired } }");
            assertNull(result.get("errors"), String.valueOf(result));
            Map<String, Object> data = map(result.get("data"));
            Map<String, Object> services = map(data.get("services"));
            assertEquals(1, services.get("totalCount"));
            Map<String, Object> lobby = map(((List<?>) services.get("items")).get(0));
            assertEquals("lobby-1", lobby.get("id"));
            assertEquals("2026-10-29T10:00:00Z", lobby.get("startedAt"), "camelCase fields read the kebab-case members");
            assertEquals(1234, ((Number) lobby.get("pid")).intValue());
            assertTrue(((String) map(data.get("config")).get("etag")).startsWith("\""));
            assertEquals("admin", map(data.get("self")).get("name"));
        }
    }

    @Test
    void everyFieldChecksTheScopeOfItsRoute() throws Exception {
        try (TestApi api = new TestApi(home)) {
            String reader = api.token("reader", Scope.SERVICES_READ);
            Map<String, Object> result = graphQl(api, reader, "{ services { totalCount } nodeStatus { nodeId } }");
            Map<String, Object> data = map(result.get("data"));
            assertEquals(1, map(data.get("services")).get("totalCount"), "the allowed field is answered");
            assertNull(data.get("nodeStatus"));
            Map<String, Object> error = map(((List<?>) result.get("errors")).get(0));
            assertEquals(List.of("nodeStatus"), error.get("path"));
            assertEquals("missing-scope", map(error.get("extensions")).get("code"));
            assertEquals(403, map(error.get("extensions")).get("status"));

            Map<String, Object> refused = graphQl(api, reader, "mutation { stopService(id: \"lobby-1\") { state } }");
            assertEquals("missing-scope", code(refused));
            assertTrue(api.backend.services.containsKey("lobby-1"));

            String writer = api.token("writer", Scope.SERVICES_WRITE);
            Map<String, Object> stopped = graphQl(api, writer, "mutation { stopService(id: \"lobby-1\") { id } }");
            assertNull(stopped.get("errors"), String.valueOf(stopped));
            assertEquals("lobby-1", map(map(stopped.get("data")).get("stopService")).get("id"));
        }
    }

    @Test
    void argumentsCannotLeaveTheirPathSegment() throws Exception {
        try (TestApi api = new TestApi(home)) {
            for (String id : List.of("lobby-1/stop", "../node", "lobby-1?limit=1", "x%2f", "")) {
                Map<String, Object> result = graphQl(api, api.admin,
                        "query($id: ID!) { service(id: $id) { id } }", Map.of("id", id));
                assertEquals("invalid-value", code(result), id);
            }
            assertEquals("not-found", code(graphQl(api, api.admin, "{ service(id: \"lobby-9\") { id } }")));
        }
    }

    @Test
    void configurationDocumentsRoundTripWithTheirEtag() throws Exception {
        try (TestApi api = new TestApi(home)) {
            Map<String, Object> config = map(map(graphQl(api, api.admin, "{ config { etag document } }").get("data"))
                    .get("config"));
            Map<String, Object> written = graphQl(api, api.admin,
                    "mutation($d: JSON!, $e: String) { putConfig(document: $d, ifMatch: $e) { etag } }",
                    Map.of("d", config.get("document"), "e", config.get("etag")));
            assertNull(written.get("errors"), String.valueOf(written));
            Map<String, Object> stale = graphQl(api, api.admin,
                    "mutation($d: JSON!) { putConfig(document: $d, ifMatch: \"\\\"stale\\\"\") { etag } }",
                    Map.of("d", config.get("document")));
            assertEquals(412, map(map(((List<?>) stale.get("errors")).get(0)).get("extensions")).get("status"));
        }
    }

    @Test
    void expensiveDocumentsAreRefusedBeforeAnyFieldRuns() throws Exception {
        try (TestApi api = new TestApi(home)) {
            StringBuilder wide = new StringBuilder("{");
            for (int i = 0; i < 120; i++) {
                wide.append(" a").append(i).append(": self { id name }");
            }
            Map<String, Object> complex = graphQl(api, api.admin, wide.append(" }").toString());
            assertNull(complex.get("data"));
            assertTrue(String.valueOf(complex.get("errors")).contains("complexity"), String.valueOf(complex));

            StringBuilder calls = new StringBuilder("{");
            for (int i = 0; i < GraphQlApi.MAX_CALLS + 1; i++) {
                calls.append(" a").append(i).append(": self { id }");
            }
            Map<String, Object> many = graphQl(api, api.admin, calls.append(" }").toString());
            assertTrue(String.valueOf(many.get("errors")).contains("too-many-calls"), String.valueOf(many));

            String tokens = "{ " + "self { id } ".repeat(GraphQlApi.MAX_TOKENS) + "}";
            Map<String, Object> parsed = graphQl(api, api.admin, tokens.substring(0, Math.min(tokens.length(),
                    GraphQlApi.MAX_CHARACTERS)));
            assertNull(parsed.get("data"));
            assertFalse(String.valueOf(parsed.get("errors")).isEmpty());

            HttpResponse<String> huge = post(api, api.admin, Map.of("query", "{" + " ".repeat(GraphQlApi.MAX_CHARACTERS) + "}"));
            assertEquals(400, huge.statusCode());
        }
    }

    @Test
    void everyFieldAfterTheFirstTakesFromTheRateLimit() throws Exception {
        ApiLimits limits = new ApiLimits(256 * 1024, 600, 120, 300, 3, 10, 64, 256, 32, 32, 120);
        try (TestApi api = new TestApi(home, s -> s.withLimits(limits))) {
            Map<String, Object> result = graphQl(api, api.admin,
                    "{ a: self { id } b: self { id } c: self { id } d: self { id } }");
            String errors = String.valueOf(result.get("errors"));
            assertTrue(errors.contains("retryAfter"), errors);
        }
    }

    @Test
    void introspectionFollowsTheSetting() throws Exception {
        String query = "{ __schema { queryType { name } } }";
        try (TestApi api = new TestApi(home)) {
            Map<String, Object> off = graphQl(api, api.admin, query);
            assertNull(off.get("data"));
            assertTrue(String.valueOf(off.get("errors")).toLowerCase(java.util.Locale.ROOT).contains("introspection"),
                    String.valueOf(off));
        }
        try (TestApi api = new TestApi(home.resolve("on"), s -> s.withGraphqlIntrospection(true))) {
            Map<String, Object> on = graphQl(api, api.admin, query);
            assertEquals("Query", map(map(map(on.get("data")).get("__schema")).get("queryType")).get("name"));
        }
    }

    @Test
    void badRequestsAreRefusedWithTheirCode() throws Exception {
        try (TestApi api = new TestApi(home)) {
            assertEquals(400, api.send("POST", GraphQlApi.PATH, api.admin, "[]").statusCode());
            assertEquals(400, api.send("POST", GraphQlApi.PATH, api.admin, "{\"query\":1}").statusCode());
            assertEquals(400, api.send("POST", GraphQlApi.PATH, api.admin, "{\"query\":\"{a}\",\"x\":1}").statusCode());
            assertEquals(426, api.get(GraphQlApi.PATH, api.admin).statusCode());
            assertEquals(401, api.send("POST", GraphQlApi.PATH, null, "{\"query\":\"{ self { id } }\"}").statusCode());
            Map<String, Object> overHttp = graphQl(api, api.admin, "subscription { events { type } }");
            assertEquals("subscription-over-http", code(overHttp));
        }
    }

    @Test
    void aSubscriptionDeliversEventsOverGraphqlTransportWs() throws Exception {
        try (TestApi api = new TestApi(home)) {
            EventStreamTest.Recorder recorder = new EventStreamTest.Recorder();
            WebSocket socket = socket(api, api.admin, recorder);
            socket.sendText("{\"type\":\"connection_init\"}", true).join();
            assertEquals("{\"type\":\"connection_ack\"}", recorder.next());
            socket.sendText("{\"type\":\"ping\"}", true).join();
            assertEquals("{\"type\":\"pong\"}", recorder.next());
            socket.sendText(subscribe("s1", "subscription { events(types: [\"service-output\"]) { type data } }"), true)
                    .join();
            // the subscription is registered once the first event reaches it
            String next = null;
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (next == null && System.nanoTime() < end) {
                api.server.publish(new ApiEvent.ServiceOutput("lobby-1", "hello vct_secret"));
                next = recorder.messages.poll(200, TimeUnit.MILLISECONDS);
            }
            assertTrue(next != null && next.contains("\"id\":\"s1\"") && next.contains("\"type\":\"next\""), next);
            assertTrue(next.contains("service-output") && next.contains("hello"), next);

            socket.sendText(subscribe("q1", "{ self { name } }"), true).join();
            String answer = nextFor(recorder, "q1");
            assertTrue(answer.contains("\"admin\""), answer);
            assertTrue(nextFor(recorder, "q1").contains("\"complete\""));

            socket.sendText("{\"type\":\"complete\",\"id\":\"s1\"}", true).join();
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            EventStreamTest.waitForStreams(api, 0);
        }
    }

    @Test
    void aSubscriptionNeedsTheScopeOfEachEventType() throws Exception {
        try (TestApi api = new TestApi(home)) {
            String reader = api.token("reader", Scope.SERVICES_READ);
            EventStreamTest.Recorder recorder = new EventStreamTest.Recorder();
            WebSocket socket = socket(api, reader, recorder);
            socket.sendText("{\"type\":\"connection_init\"}", true).join();
            recorder.next();
            socket.sendText(subscribe("s1", "subscription { events(types: [\"node-log\"]) { type } }"), true).join();
            String error = recorder.next();
            assertTrue(error.contains("\"type\":\"error\"") && error.contains("missing-scope"), error);
            socket.sendText(subscribe("s2", "subscription { events(types: [\"nope\"]) { type } }"), true).join();
            assertTrue(recorder.next().contains("invalid-value"));
        }
    }

    @Test
    void theProtocolRulesAreEnforced() throws Exception {
        try (TestApi api = new TestApi(home)) {
            EventStreamTest.Recorder early = new EventStreamTest.Recorder();
            WebSocket first = socket(api, api.admin, early);
            first.sendText(subscribe("s1", "{ self { id } }"), true).join();
            assertEquals(4401, early.closed.get(10, TimeUnit.SECONDS));

            EventStreamTest.Recorder twice = new EventStreamTest.Recorder();
            WebSocket second = socket(api, api.admin, twice);
            second.sendText("{\"type\":\"connection_init\"}", true).join();
            twice.next();
            second.sendText("{\"type\":\"connection_init\"}", true).join();
            assertEquals(4429, twice.closed.get(10, TimeUnit.SECONDS));

            EventStreamTest.Recorder garbage = new EventStreamTest.Recorder();
            socket(api, api.admin, garbage).sendText("not json", true).join();
            assertEquals(4400, garbage.closed.get(10, TimeUnit.SECONDS));

            CompletableFuture<WebSocket> noProtocol = api.http.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + api.admin)
                    .buildAsync(URI.create("ws://127.0.0.1:" + api.address.getPort() + GraphQlApi.PATH),
                            new EventStreamTest.Recorder());
            CompletionException e = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class,
                    noProtocol::join);
            assertInstanceOf(WebSocketHandshakeException.class, e.getCause());
            assertEquals(400, ((WebSocketHandshakeException) e.getCause()).getResponse().statusCode());
        }
    }

    @Test
    void aRevokedTokenClosesItsGraphQlSocket() throws Exception {
        try (TestApi api = new TestApi(home)) {
            String reader = api.token("reader", Scope.SERVICES_READ);
            EventStreamTest.Recorder recorder = new EventStreamTest.Recorder();
            WebSocket socket = socket(api, reader, recorder);
            socket.sendText("{\"type\":\"connection_init\"}", true).join();
            recorder.next();
            EventStreamTest.waitForStreams(api, 1);
            api.tokens.revoke("reader");
            assertEquals(1008, recorder.closed.get(10, TimeUnit.SECONDS));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static WebSocket socket(TestApi api, String token, EventStreamTest.Recorder recorder) throws Exception {
        return api.http.newWebSocketBuilder().header("Authorization", "Bearer " + token)
                .subprotocols(GraphQlSocketHandler.PROTOCOL)
                .buildAsync(URI.create("ws://127.0.0.1:" + api.address.getPort() + GraphQlApi.PATH), recorder)
                .get(10, TimeUnit.SECONDS);
    }

    private static String subscribe(String id, String query) {
        return new String(ApiJson.write(Map.of("id", id, "type", "subscribe", "payload", Map.of("query", query))),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String nextFor(EventStreamTest.Recorder recorder, String id) throws InterruptedException {
        while (true) {
            String message = recorder.next();
            if (message.contains("\"id\":\"" + id + "\"")) {
                return message;
            }
        }
    }

    private static HttpResponse<String> post(TestApi api, String token, Map<String, Object> body) throws Exception {
        api.nanos.addAndGet(60_000_000_000L);
        return api.send("POST", GraphQlApi.PATH, token,
                new String(ApiJson.write(body), java.nio.charset.StandardCharsets.UTF_8));
    }

    static Map<String, Object> graphQl(TestApi api, String token, String query) throws Exception {
        return graphQl(api, token, query, Map.of());
    }

    static Map<String, Object> graphQl(TestApi api, String token, String query, Map<String, Object> variables)
            throws Exception {
        HttpResponse<String> response = post(api, token, Map.of("query", query, "variables", variables));
        assertEquals(200, response.statusCode(), response.body());
        return map(ApiJson.mapper().readValue(response.body(), Map.class));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        assertInstanceOf(Map.class, value, String.valueOf(value));
        return (Map<String, Object>) value;
    }

    private static String code(Map<String, Object> result) {
        Object errors = result.get("errors");
        assertInstanceOf(List.class, errors, String.valueOf(result));
        return String.valueOf(map(map(((List<?>) errors).get(0)).get("extensions")).get("code"));
    }
}
