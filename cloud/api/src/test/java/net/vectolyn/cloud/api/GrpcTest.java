// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.TrustManagerFactory;
import net.vectolyn.cloud.api.auth.ApiTokens;
import net.vectolyn.cloud.api.auth.Scope;
import net.vectolyn.cloud.api.event.ApiEvent;
import net.vectolyn.cloud.api.grpc.Proto;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.ConfigService;
import net.vectolyn.cloud.config.model.TlsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The gRPC listener ({@code api.grpc-port}): unary calls run the REST routes with the caller's
 * token, REST errors become gRPC status codes, broken requests are refused before anything runs,
 * {@code Events} streams events and ends with {@code UNAUTHENTICATED} when its token is revoked,
 * and with TLS the listener speaks HTTP/2 through ALPN.
 */
@Timeout(120)
class GrpcTest {

    @TempDir
    Path home;

    private TestApi api() throws Exception {
        return new TestApi(home, s -> s.withGrpcPort(-1));
    }

    /** A server with a short idle timeout (2 s) and a small body limit (1 KiB). */
    private TestApi strictApi() throws Exception {
        return new TestApi(home, s -> {
            ApiLimits d = s.limits();
            return s.withGrpcPort(-1).withLimits(new ApiLimits(1024, d.requestsPerMinutePerIp(), d.burstPerIp(),
                    d.requestsPerMinutePerToken(), d.burstPerToken(), d.authFailuresPerMinute(),
                    d.maxConcurrentRequests(), d.maxConnections(), d.maxConnectionsPerIp(), d.maxWebSockets(), 2));
        });
    }

    private static GrpcTestClient client(TestApi api) throws InterruptedException {
        return new GrpcTestClient(api.server.grpcAddress().orElseThrow(), null);
    }

    @Test
    void theListenerIsOffUnlessAPortIsSet() throws Exception {
        try (TestApi api = new TestApi(home)) {
            assertTrue(api.server.grpcAddress().isEmpty());
        }
    }

    @Test
    void unaryCallsAnswerLikeTheirRestRoute() throws Exception {
        try (TestApi api = api(); GrpcTestClient client = client(api)) {
            GrpcTestClient.Reply list = client.unary("ListServices", api.admin, Map.of());
            assertEquals(0, list.status(), String.valueOf(list.trailers()));
            assertEquals("200", list.httpStatus());
            assertEquals("application/grpc", list.headers().get("content-type").toString());
            Map<String, Object> services = list.message("ListServices");
            assertEquals(1, services.get("total_count"));
            Map<?, ?> lobby = (Map<?, ?>) ((List<?>) services.get("items")).getFirst();
            assertEquals("lobby-1", lobby.get("id"));
            assertEquals("2026-10-29T10:00:00Z", lobby.get("started_at"));
            assertEquals(1234L, lobby.get("pid"));

            GrpcTestClient.Reply self = client.unary("GetSelf", api.admin, Map.of());
            assertEquals("admin", self.message("GetSelf").get("name"));

            GrpcTestClient.Reply config = client.unary("GetDocument", api.admin, Map.of("kind", "DOCUMENT_KIND_CONFIG"));
            assertEquals(0, config.status(), String.valueOf(config.trailers()));
            Map<String, Object> document = config.message("GetDocument");
            assertTrue(((String) document.get("etag")).startsWith("\""));
            assertTrue(((String) document.get("json")).contains("\"node-id\""), String.valueOf(document.get("json")));
        }
    }

    @Test
    void restErrorsBecomeGrpcStatusCodes() throws Exception {
        try (TestApi api = api(); GrpcTestClient client = client(api)) {
            String reader = api.token("reader", Scope.SERVICES_READ);
            GrpcTestClient.Reply refused = client.unary("StopService", reader, Map.of("id", "lobby-1"));
            assertEquals(GrpcStatus.PERMISSION_DENIED, refused.status());
            assertEquals("missing-scope", refused.code());
            assertTrue(api.backend.services.containsKey("lobby-1"), "nothing ran");

            GrpcTestClient.Reply missing = client.unary("GetService", reader, Map.of("id", "nope-1"));
            assertEquals(GrpcStatus.NOT_FOUND, missing.status());

            GrpcTestClient.Reply escaping = client.unary("GetService", reader, Map.of("id", "../admin"));
            assertEquals(GrpcStatus.INVALID_ARGUMENT, escaping.status(), String.valueOf(escaping.trailers()));

            GrpcTestClient.Reply kind = client.unary("ListDocuments", api.admin, Map.of());
            assertEquals(GrpcStatus.INVALID_ARGUMENT, kind.status());
        }
    }

    @Test
    void callsWithoutAValidTokenAreUnauthenticated() throws Exception {
        try (TestApi api = api(); GrpcTestClient client = client(api)) {
            GrpcTestClient.Reply none = client.unary("GetSelf", null, Map.of());
            assertEquals(GrpcStatus.UNAUTHENTICATED, none.status());
            assertEquals("unauthorized", none.code());
            assertEquals(List.of(), none.messages());
            GrpcTestClient.Reply wrong = client.unary("GetSelf", "vct_wrong", Map.of());
            assertEquals(GrpcStatus.UNAUTHENTICATED, wrong.status());
            String message = wrong.trailers().get("grpc-message").toString();
            assertFalse(message.contains("vct_wrong"), "the token is not echoed");
        }
    }

    @Test
    void brokenRequestsAreRefusedBeforeAnythingRuns() throws Exception {
        try (TestApi api = api(); GrpcTestClient client = client(api)) {
            GrpcTestClient.Reply unknown = client.open(GrpcTestClient.headers("Nope", api.admin)
                    .path("/vectolyn.api.v1.CloudService/Nope"), GrpcStatus.frame(new byte[0])).reply();
            assertEquals(GrpcStatus.UNIMPLEMENTED, unknown.status());

            byte[] compressed = GrpcStatus.frame(new byte[0]);
            compressed[0] = 1;
            assertEquals(GrpcStatus.UNIMPLEMENTED,
                    client.open(GrpcTestClient.headers("GetSelf", api.admin), compressed).reply().status());

            byte[] truncated = {0, 0, 0, 0, 9, 1};
            assertEquals(GrpcStatus.INTERNAL,
                    client.open(GrpcTestClient.headers("GetSelf", api.admin), truncated).reply().status());

            byte[] twoMessages = new byte[10];
            assertEquals(GrpcStatus.INTERNAL,
                    client.open(GrpcTestClient.headers("GetSelf", api.admin), twoMessages).reply().status());

            byte[] invalidUtf8 = GrpcStatus.frame(new byte[] {10, 1, (byte) 0xFF});
            GrpcTestClient.Reply garbage = client.open(GrpcTestClient.headers("GetService", api.admin), invalidUtf8).reply();
            assertEquals(GrpcStatus.INTERNAL, garbage.status());
            assertEquals("invalid-message", garbage.code());

            Http2Headers get = GrpcTestClient.headers("GetSelf", api.admin).method("GET");
            assertEquals("405", client.open(get, null).reply().httpStatus());
            Http2Headers json = GrpcTestClient.headers("GetSelf", api.admin).set("content-type", "application/json");
            assertEquals("415", client.open(json, GrpcStatus.frame(new byte[0])).reply().httpStatus());
            Http2Headers web = GrpcTestClient.headers("GetSelf", api.admin).set("content-type", "application/grpc-web");
            assertEquals("415", client.open(web, GrpcStatus.frame(new byte[0])).reply().httpStatus());
            Http2Headers proto = GrpcTestClient.headers("GetSelf", api.admin)
                    .set("content-type", "application/grpc+proto; charset=utf-8");
            assertEquals(0, client.open(proto, GrpcStatus.frame(new byte[0])).reply().status());

            byte[] large = GrpcStatus.frame(new byte[api.server.limits().maxBodyBytes() + 1]);
            GrpcTestClient.Reply tooLarge = client.open(GrpcTestClient.headers("GetSelf", api.admin), large).reply();
            assertEquals(GrpcStatus.RESOURCE_EXHAUSTED, tooLarge.status());
            assertEquals("body-too-large", tooLarge.code());

            assertEquals(0, client.unary("GetSelf", api.admin, Map.of()).status(), "the connection still works");
        }
    }

    @Test
    void aConnectionWithoutAnAuthenticatedCallIsClosedWhenIdle() throws Exception {
        try (TestApi api = strictApi(); GrpcTestClient client = client(api)) {
            // A stream without token that the client never finishes: answered, reset, and it keeps nothing open.
            GrpcTestClient.Call refused = client.open(GrpcTestClient.headers("GetSelf", null), null, false);
            assertEquals(GrpcStatus.UNAUTHENTICATED, refused.reply().status());
            api.nanos.addAndGet(3_000_000_000L);
            assertTrue(client.closedWithin(10), "the idle connection is closed");
        }
    }

    @Test
    void anOpenEventsStreamKeepsItsConnection() throws Exception {
        try (TestApi api = strictApi(); GrpcTestClient client = client(api)) {
            byte[] request = GrpcStatus.frame(Proto.encode(GrpcTestClient.SCHEMA.methods().get("Events").request(),
                    Map.of("types", List.of("service-output"))));
            GrpcTestClient.Call call = client.open(GrpcTestClient.headers("Events", api.admin), request);
            EventStreamTest.waitForStreams(api, 1);
            api.nanos.addAndGet(10_000_000_000L);
            assertFalse(client.closedWithin(3), "an open call is activity");
            api.server.publish(new ApiEvent.ServiceOutput("lobby-1", "still here"));
            assertNotNull(call.messages.poll(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void anUnfinishedRequestTimesOutAndBodiesShareTheConnectionBudget() throws Exception {
        try (TestApi api = strictApi(); GrpcTestClient client = client(api)) {
            GrpcTestClient.Call slow = client.open(GrpcTestClient.headers("GetSelf", api.admin), new byte[1000], false);
            GrpcTestClient.Call second = client.open(GrpcTestClient.headers("GetSelf", api.admin), new byte[100], false);
            GrpcTestClient.Reply busy = second.reply();
            assertEquals(GrpcStatus.UNAVAILABLE, busy.status());
            assertEquals("busy", busy.code());
            GrpcTestClient.Reply timedOut = slow.reply();
            assertEquals(GrpcStatus.DEADLINE_EXCEEDED, timedOut.status());
            assertEquals("request-timeout", timedOut.code());
            assertEquals(0, client.unary("GetSelf", api.admin, Map.of()).status(), "the budget is free again");
        }
    }

    @Test
    void eventsStreamUntilTheTokenIsRevoked() throws Exception {
        try (TestApi api = api(); GrpcTestClient client = client(api)) {
            String reader = api.token("reader", Scope.SERVICES_READ);
            byte[] request = GrpcStatus.frame(Proto.encode(GrpcTestClient.SCHEMA.methods().get("Events").request(),
                    Map.of("types", List.of("service-output", "node-log"), "services", List.of("lobby-1"))));
            GrpcTestClient.Call refused = client.open(GrpcTestClient.headers("Events", reader), request);
            assertEquals(GrpcStatus.PERMISSION_DENIED, refused.reply().status(), "node-log needs admin");

            byte[] allowed = GrpcStatus.frame(Proto.encode(GrpcTestClient.SCHEMA.methods().get("Events").request(),
                    Map.of("types", List.of("service-output"), "services", List.of("lobby-1"))));
            GrpcTestClient.Call call = client.open(GrpcTestClient.headers("Events", reader), allowed);
            assertEquals("200", call.headers.get(10, TimeUnit.SECONDS).status().toString());
            EventStreamTest.waitForStreams(api, 1);
            api.server.publish(new ApiEvent.ServiceOutput("lobby-2", "not this service"));
            api.server.publish(new ApiEvent.ServiceOutput("lobby-1", "hello"));
            byte[] first = call.messages.poll(10, TimeUnit.SECONDS);
            assertNotNull(first);
            Map<String, Object> event = Proto.decode(GrpcTestClient.SCHEMA.methods().get("Events").response(), first);
            assertEquals("service-output", event.get("type"));
            assertTrue(((String) event.get("json")).contains("hello"), String.valueOf(event));

            api.tokens.revoke("reader");
            GrpcTestClient.Reply end = call.reply();
            assertEquals(GrpcStatus.UNAUTHENTICATED, end.status());
            assertTrue(end.messages().isEmpty(), "nothing after the hello event");
            EventStreamTest.waitForStreams(api, 0);
        }
    }

    @Test
    void withTlsTheListenerNegotiatesHttp2ThroughAlpn() throws Exception {
        CloudHome cloudHome = CloudHome.of(home);
        ApiTls.Material tls = ApiTls.prepare(cloudHome, new TlsConfig(true, "secrets/api.p12"), "127.0.0.1", Optional.empty());
        try (ConfigService config = ConfigService.open(cloudHome)) {
            ApiTokens tokens = ApiTokens.open(cloudHome);
            String token = tokens.create("admin", EnumSet.of(Scope.ADMIN)).token().reveal();
            ApiServer server = ApiServer.builder(config, new FakeBackend(), tokens)
                    .settings(new ApiSettings("127.0.0.1", 0, Optional.of(tls), List.of(), ApiLimits.defaults())
                            .withGrpcPort(-1)).build();
            try {
                server.start();
                try (GrpcTestClient client = new GrpcTestClient(server.grpcAddress().orElseThrow(),
                        trusting(home.resolve("secrets/api.p12"), tls))) {
                    GrpcTestClient.Reply self = client.unary("GetSelf", token, Map.of());
                    assertEquals(0, self.status(), String.valueOf(self.trailers()));
                    assertEquals("admin", self.message("GetSelf").get("name"));
                    assertEquals("h2", client.alpn());
                }
            } finally {
                server.close();
            }
        }
    }

    private static SslContext trusting(Path keystore, ApiTls.Material tls) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, tls.password().reveal().toCharArray());
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        return SslContextBuilder.forClient().trustManager(trust)
                .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                .applicationProtocolConfig(new ApplicationProtocolConfig(ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2))
                .build();
    }
}
