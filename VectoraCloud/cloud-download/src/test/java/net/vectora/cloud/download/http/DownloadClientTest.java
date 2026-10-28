package net.vectora.cloud.download.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.vectora.cloud.download.DownloadException;
import net.vectora.cloud.download.HostNotAllowedException;
import net.vectora.cloud.download.HttpStatusException;
import net.vectora.cloud.download.SizeLimitExceededException;
import org.junit.jupiter.api.Test;

class DownloadClientTest {

    private static final HostAllowlist HOSTS = HostAllowlist.of("api.example.org", "cdn.example.org");
    private static final String API = "https://api.example.org/file";

    private final List<Duration> sleeps = new ArrayList<>();

    private DownloadClient client(FakeTransport transport) {
        return new DownloadClient(transport, DownloadSettings.defaults(), sleeps::add);
    }

    @Test
    void sendsTheVectoraUserAgent() throws Exception {
        FakeTransport transport = new FakeTransport().ok(API, "hello");
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), client(transport).fetchBytes(URI.create(API), HOSTS, 100));
        String agent = transport.requestHeaders().get(0).get("User-Agent");
        assertTrue(agent.startsWith("VectoraCloud/"), agent);
        assertTrue(agent.endsWith(" (+https://github.com/VectoDE/Vectora-Network)"), agent);
    }

    @Test
    void userAgentHasTheDocumentedShape() {
        assertEquals("VectoraCloud/1.2.3 (+https://github.com/VectoDE/Vectora-Network)", DownloadSettings.userAgent("1.2.3"));
    }

    @Test
    void refusesPlainHttp() {
        FakeTransport transport = new FakeTransport();
        assertThrows(HostNotAllowedException.class,
                () -> client(transport).fetchBytes(URI.create("http://api.example.org/file"), HOSTS, 100));
        assertTrue(transport.requests().isEmpty(), "nothing may be sent over plain HTTP");
    }

    @Test
    void refusesHostOutsideTheAllowlist() {
        FakeTransport transport = new FakeTransport();
        assertThrows(HostNotAllowedException.class,
                () -> client(transport).fetchBytes(URI.create("https://evil.example.net/file"), HOSTS, 100));
        assertTrue(transport.requests().isEmpty());
    }

    @Test
    void followsRedirectToAnAllowedHost() throws Exception {
        FakeTransport transport = new FakeTransport()
                .redirect(API, 302, "https://cdn.example.org/blob")
                .ok("https://cdn.example.org/blob", "data");
        assertArrayEquals("data".getBytes(StandardCharsets.UTF_8), client(transport).fetchBytes(URI.create(API), HOSTS, 100));
        assertEquals(2, transport.requests().size());
    }

    @Test
    void resolvesRelativeRedirects() throws Exception {
        FakeTransport transport = new FakeTransport()
                .redirect(API, 301, "/other")
                .ok("https://api.example.org/other", "x");
        assertArrayEquals(new byte[] {'x'}, client(transport).fetchBytes(URI.create(API), HOSTS, 100));
    }

    @Test
    void rejectsRedirectToAForeignHost() {
        FakeTransport transport = new FakeTransport().redirect(API, 302, "https://mirror.evil.example.net/file");
        HostNotAllowedException e = assertThrows(HostNotAllowedException.class,
                () -> client(transport).fetchBytes(URI.create(API), HOSTS, 100));
        assertTrue(e.getMessage().contains("mirror.evil.example.net"), e.getMessage());
        assertEquals(1, transport.requests().size(), "the foreign host must never be contacted");
        assertTrue(sleeps.isEmpty(), "a refused host is not retried");
    }

    @Test
    void rejectsRedirectDowngradeToHttp() {
        FakeTransport transport = new FakeTransport().redirect(API, 302, "http://cdn.example.org/blob");
        assertThrows(HostNotAllowedException.class, () -> client(transport).fetchBytes(URI.create(API), HOSTS, 100));
    }

    @Test
    void rejectsRedirectToAnotherPort() {
        FakeTransport transport = new FakeTransport().redirect(API, 302, "https://cdn.example.org:8443/blob");
        assertThrows(HostNotAllowedException.class, () -> client(transport).fetchBytes(URI.create(API), HOSTS, 100));
    }

    @Test
    void stopsAfterTooManyRedirects() {
        FakeTransport transport = new FakeTransport().redirect(API, 302, API);
        DownloadException e = assertThrows(DownloadException.class,
                () -> new DownloadClient(transport, DownloadSettings.defaults().withMaxAttempts(1), sleeps::add)
                        .fetchBytes(URI.create(API), HOSTS, 100));
        assertTrue(e.getMessage().contains("redirects"), e.getMessage());
        assertEquals(DownloadClient.MAX_REDIRECTS + 1, transport.requests().size());
    }

    @Test
    void doesNotRetryClientErrors() {
        FakeTransport transport = new FakeTransport().status(API, 404);
        HttpStatusException e = assertThrows(HttpStatusException.class,
                () -> client(transport).fetchBytes(URI.create(API), HOSTS, 100));
        assertEquals(404, e.status());
        assertEquals(1, transport.requests().size());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void retriesServerErrorsWithBackoff() throws Exception {
        FakeTransport transport = new FakeTransport().status(API, 503).status(API, 502).ok(API, "ok");
        assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), client(transport).fetchBytes(URI.create(API), HOSTS, 100));
        assertEquals(3, transport.requests().size());
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), sleeps);
    }

    @Test
    void retriesNetworkErrors() throws Exception {
        FakeTransport transport = new FakeTransport().fail(API).ok(API, "ok");
        assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), client(transport).fetchBytes(URI.create(API), HOSTS, 100));
        assertEquals(2, transport.requests().size());
    }

    @Test
    void givesUpAfterMaxAttempts() {
        FakeTransport transport = new FakeTransport().status(API, 500);
        HttpStatusException e = assertThrows(HttpStatusException.class,
                () -> client(transport).fetchBytes(URI.create(API), HOSTS, 100));
        assertEquals(500, e.status());
        assertEquals(3, transport.requests().size());
    }

    @Test
    void rejectsAnnouncedOversizedBody() {
        FakeTransport transport = new FakeTransport().ok(API, new byte[200]);
        assertThrows(SizeLimitExceededException.class, () -> client(transport).fetchBytes(URI.create(API), HOSTS, 100));
        assertEquals(1, transport.requests().size(), "a size violation is not retried");
    }

    @Test
    void rejectsOversizedBodyWithoutContentLength() {
        FakeTransport transport = new FakeTransport()
                .answer(API, new FakeTransport.Response(200, Map.of(), new byte[200]));
        assertThrows(SizeLimitExceededException.class, () -> client(transport).fetchBytes(URI.create(API), HOSTS, 100));
    }

    @Test
    void rejectsRedirectWithoutLocation() {
        FakeTransport transport = new FakeTransport().status(API, 302);
        assertThrows(DownloadException.class, () -> client(transport).fetchBytes(URI.create(API), HOSTS, 100));
    }

    @Test
    void backoffDoublesUpToTheCap() {
        DownloadSettings settings = DownloadSettings.defaults();
        assertEquals(Duration.ofSeconds(1), settings.backoffBefore(2));
        assertEquals(Duration.ofSeconds(2), settings.backoffBefore(3));
        assertEquals(Duration.ofSeconds(8), settings.backoffBefore(5));
        assertEquals(Duration.ofSeconds(10), settings.backoffBefore(20));
    }

    @Test
    void allowlistChecksSchemeHostPortAndUserInfo() {
        assertTrue(HOSTS.permits(URI.create("https://API.example.org/x")));
        assertTrue(HOSTS.permits(URI.create("https://api.example.org:443/x")));
        assertFalse(HOSTS.permits(URI.create("https://user@api.example.org/x")));
        assertFalse(HOSTS.permits(URI.create("https://api.example.org.evil.net/x")));
        assertFalse(HOSTS.permits(URI.create("ftp://api.example.org/x")));
        assertFalse(HOSTS.permits(URI.create("/relative")));
    }

    @Test
    void handlerErrorsAreRetriedButSecurityErrorsAreNot() {
        FakeTransport transport = new FakeTransport().ok(API, "data");
        int[] calls = {0};
        assertThrows(IOException.class, () -> client(transport).get(URI.create(API), HOSTS, 100, (body, length) -> {
            calls[0]++;
            throw new IOException("disk full");
        }));
        assertEquals(3, calls[0]);
    }
}
