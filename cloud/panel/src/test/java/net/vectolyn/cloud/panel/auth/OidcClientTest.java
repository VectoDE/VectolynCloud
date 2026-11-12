// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.vectolyn.cloud.api.http.ApiJson;
import net.vectolyn.cloud.config.secret.Secret;
import net.vectolyn.cloud.panel.config.PanelConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The single sign-on client against a small provider on loopback. */
public class OidcClientTest {

    /** A provider with discovery, JWKS and a token endpoint that signs whatever the test sets. */
    public static final class TestProvider implements AutoCloseable {

        public final HttpServer http;
        public final String issuer;
        public final KeyPair keys;
        public final AtomicReference<String> claims = new AtomicReference<>();
        public final AtomicReference<String> lastTokenRequest = new AtomicReference<>();
        public final AtomicReference<String> lastAuthorization = new AtomicReference<>();

        public TestProvider() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            keys = generator.generateKeyPair();
            http = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
            issuer = "http://127.0.0.1:" + http.getAddress().getPort();
            http.createContext("/.well-known/openid-configuration", ex -> reply(ex, "{\"issuer\":\"" + issuer
                    + "\",\"authorization_endpoint\":\"" + issuer + "/authorize\",\"token_endpoint\":\"" + issuer
                    + "/token\",\"jwks_uri\":\"" + issuer + "/jwks\"}"));
            http.createContext("/jwks", ex -> reply(ex, new String(ApiJson.write(AuthPrimitivesTest.jwks(keys, "k1")),
                    StandardCharsets.UTF_8)));
            http.createContext("/token", ex -> {
                lastTokenRequest.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                lastAuthorization.set(ex.getRequestHeaders().getFirst("Authorization"));
                try {
                    reply(ex, "{\"id_token\":\"" + AuthPrimitivesTest.sign(keys, "k1", claims.get()) + "\"}");
                } catch (Exception e) {
                    throw new IOException(e);
                }
            });
            http.start();
        }

        private static void reply(com.sun.net.httpserver.HttpExchange ex, String json) throws IOException {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        }

        /**
         * Claims of an ID token for a nonce.
         *
         * @param subject the user
         * @param nonce   the nonce
         * @return JSON
         */
        public String idToken(String subject, String nonce) {
            long now = Clock.systemUTC().instant().getEpochSecond();
            return "{\"iss\":\"" + issuer + "\",\"aud\":\"panel\",\"sub\":\"" + subject + "\",\"nonce\":\"" + nonce
                    + "\",\"preferred_username\":\"" + subject + "\",\"exp\":" + (now + 300) + ",\"iat\":" + now + "}";
        }

        @Override
        public void close() {
            http.stop(0);
        }
    }

    private TestProvider provider;

    @BeforeEach
    void start() throws Exception {
        provider = new TestProvider();
    }

    @AfterEach
    void stop() {
        provider.close();
    }

    @Test
    void theCodeFlowUsesPkceStateAndNonceAndChecksTheIdToken() throws Exception {
        OidcClient client = new OidcClient(new PanelConfig.Oidc(provider.issuer, "panel", null, null),
                Secret.of("s3cret"), "http://localhost:8090", Clock.systemUTC());
        OidcClient.Attempt attempt = client.start();
        assertTrue(attempt.url().startsWith(provider.issuer + "/authorize?response_type=code&client_id=panel"), attempt.url());
        assertTrue(attempt.url().contains("code_challenge_method=S256"));
        assertTrue(attempt.url().contains("&state=" + attempt.state()));
        assertTrue(attempt.url().contains("scope=openid+profile+email"), attempt.url());

        provider.claims.set(provider.idToken("u-1", attempt.nonce()));
        OidcClient.Identity identity = client.finish(attempt, "abc/def");
        assertEquals("u-1", identity.subject());
        assertEquals(provider.issuer, identity.issuer());
        assertTrue(provider.lastTokenRequest.get().contains("code_verifier=" + attempt.verifier()));
        assertTrue(provider.lastTokenRequest.get().contains("code=abc%2Fdef"));
        assertTrue(provider.lastAuthorization.get().startsWith("Basic "));

        provider.claims.set(provider.idToken("u-1", "another-nonce"));
        assertThrows(OidcClient.OidcException.class, () -> client.finish(attempt, "abc"), "replayed or foreign token");
        assertEquals(List.of("openid", "profile", "email"), client.scopes());
    }

    @Test
    void anotherIssuerInTheDiscoveryIsRefused() {
        OidcClient client = new OidcClient(new PanelConfig.Oidc(provider.issuer + "/x", "panel", null, null),
                Secret.of("s"), "http://localhost:8090", Clock.systemUTC());
        assertThrows(OidcClient.OidcException.class, client::start);
    }
}
