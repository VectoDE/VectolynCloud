package net.vectolyn.cloud.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import net.vectolyn.cloud.api.auth.ApiTokens;
import net.vectolyn.cloud.api.auth.Scope;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.ConfigService;
import net.vectolyn.cloud.config.model.TlsConfig;
import net.vectolyn.cloud.config.secret.OwnerOnlyPermissions;
import net.vectolyn.cloud.config.secret.Secret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(120)
class ApiTlsTest {

    @TempDir
    Path home;

    @Test
    void generatesASelfSignedKeystoreAndServesHttps() throws Exception {
        CloudHome cloudHome = CloudHome.of(home);
        ApiTls.Material tls = ApiTls.prepare(cloudHome, new TlsConfig(true, "secrets/api.p12"), "0.0.0.0", Optional.empty());
        assertTrue(tls.generated());
        assertTrue(Files.isRegularFile(home.resolve("secrets/api.p12")));
        assertTrue(OwnerOnlyPermissions.isOwnerOnly(home.resolve("secrets/api-keystore.password")));
        assertTrue(tls.fingerprint().matches("([0-9A-F]{2}:){31}[0-9A-F]{2}"), tls.fingerprint());
        // A second start reuses the keystore and its password.
        ApiTls.Material again = ApiTls.prepare(cloudHome, new TlsConfig(true, "secrets/api.p12"), "0.0.0.0", Optional.empty());
        assertEquals(tls.fingerprint(), again.fingerprint());

        // Outside loopback TLS is required; with key material the check passes (no bind here, so no
        // firewall prompt on a developer machine).
        ApiServer.bindAddress(new ApiSettings("0.0.0.0", 0, Optional.of(tls), List.of(), ApiLimits.defaults()));
        assertThrows(ApiStartException.class, () -> ApiServer.bindAddress(
                new ApiSettings("0.0.0.0", 0, Optional.empty(), List.of(), ApiLimits.defaults())));

        try (ConfigService config = ConfigService.open(cloudHome)) {
            ApiTokens tokens = ApiTokens.open(cloudHome);
            String token = tokens.create("admin", EnumSet.of(Scope.ADMIN)).token().reveal();
            ApiServer server = ApiServer.builder(config, new FakeBackend(), tokens)
                    .settings(new ApiSettings("127.0.0.1", 0, Optional.of(tls), List.of(), ApiLimits.defaults())).build();
            try {
                int port = server.start().getPort();
                try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .sslContext(trusting(home.resolve("secrets/api.p12"), tls.password()))
                        .connectTimeout(Duration.ofSeconds(5)).build()) {
                    HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                                    URI.create("https://127.0.0.1:" + port + "/api/v1/services"))
                            .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(20)).build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, response.statusCode());
                    assertTrue(response.headers().firstValue("Strict-Transport-Security").isPresent());
                }
                // Plain HTTP on the TLS port gets no API answer.
                try (HttpClient plain = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5)).build()) {
                    assertThrows(IOException.class, () -> plain.send(HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + port + "/api/v1/services"))
                            .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(10)).build(),
                            HttpResponse.BodyHandlers.ofString()));
                }
            } finally {
                server.close();
            }
        }
    }

    @Test
    void anExistingKeystoreNeedsItsPassword() throws Exception {
        CloudHome cloudHome = CloudHome.of(home);
        Path keystore = home.resolve("certs/own.p12");
        Secret password = Secret.of("own-keystore-password-1234");
        ApiTls.generate(keystore, password, List.of("localhost", "127.0.0.1"));
        IOException missing = assertThrows(IOException.class,
                () -> ApiTls.prepare(cloudHome, new TlsConfig(true, "certs/own.p12"), "127.0.0.1", Optional.empty()));
        assertTrue(missing.getMessage().contains(ApiTls.PASSWORD_KEY));
        IOException wrong = assertThrows(IOException.class, () -> ApiTls.prepare(cloudHome,
                new TlsConfig(true, "certs/own.p12"), "127.0.0.1", Optional.of(Secret.of("wrong-password-123456"))));
        assertFalse(wrong.getMessage().contains("wrong-password"), "the password is never echoed");
        ApiTls.Material ok = ApiTls.prepare(cloudHome, new TlsConfig(true, "certs/own.p12"), "127.0.0.1",
                Optional.of(password));
        assertFalse(ok.generated());
    }

    @Test
    void theKeystoreStaysInsideTheHome() {
        CloudHome cloudHome = CloudHome.of(home);
        for (String bad : new String[] {"../outside.p12", "/etc/keystore.p12", "C:/keystore.p12", "a/../../b.p12", "",
                "a\\b.p12"}) {
            assertThrows(IllegalArgumentException.class, () -> ApiTls.keystorePath(cloudHome, bad), bad);
        }
        assertEquals(home.toAbsolutePath().normalize().resolve("secrets/api.p12"),
                ApiTls.keystorePath(cloudHome, "secrets/api.p12"));
    }

    @Test
    void subjectNamesSkipWildcards() {
        assertEquals(List.of("localhost", "127.0.0.1", "::1", "cloud.example.org"), ApiTls.subjectNames("cloud.example.org"));
        assertEquals(List.of("localhost", "127.0.0.1", "::1", "10.0.0.5"), ApiTls.subjectNames("10.0.0.5"));
        assertFalse(ApiTls.subjectNames("0.0.0.0").contains("0.0.0.0"));
    }

    private static SSLContext trusting(Path keystore, Secret password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, password.reveal().toCharArray());
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }
}
