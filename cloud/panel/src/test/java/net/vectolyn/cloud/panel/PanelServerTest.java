// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import net.vectolyn.cloud.panel.config.PanelConfig;
import net.vectolyn.cloud.panel.config.PanelConfigLoader;
import net.vectolyn.cloud.panel.web.WebAssets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PanelServerTest {

    static final String PAGE = "<!doctype html><html><head><script>self.a=1</script></head><body>"
            + "<p>" + "panel ".repeat(200) + "</p></body></html>";

    private final HttpClient client = HttpClient.newHttpClient();
    private PanelServer server;
    private String base;

    static Map<String, byte[]> files() {
        return Map.of(
                "index.html", PAGE.getBytes(StandardCharsets.UTF_8),
                "services/index.html", "<!doctype html><title>s</title>".getBytes(StandardCharsets.UTF_8),
                "404.html", "<!doctype html><title>missing</title>".getBytes(StandardCharsets.UTF_8),
                "_next/static/chunks/app.js", "console.log(1)".getBytes(StandardCharsets.UTF_8));
    }

    /** Settings with port 0 (a free port), which panel.yml itself does not allow. */
    static PanelConfigLoader.Settings settings(String bind, String publicUrl) {
        return new PanelConfigLoader.Settings(new PanelConfig(1, true, bind, 0, publicUrl),
                PanelConfigLoader.origin(publicUrl));
    }

    @BeforeEach
    void start() throws Exception {
        server = PanelServer.builder(settings("127.0.0.1", null)).version("1.2.3")
                .assets(WebAssets.of(files(), false)).build();
        InetSocketAddress address = server.start();
        base = "http://127.0.0.1:" + address.getPort();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<byte[]> get(String path, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).GET();
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void thePageComesWithItsHashInTheCspAndTheSecurityHeaders() throws Exception {
        HttpResponse<byte[]> response = get("/");
        assertEquals(200, response.statusCode());
        assertEquals(PAGE, new String(response.body(), StandardCharsets.UTF_8));
        String csp = response.headers().firstValue("Content-Security-Policy").orElseThrow();
        assertTrue(csp.contains("script-src 'self' 'sha256-"), csp);
        assertFalse(csp.contains("unsafe-inline"), csp);
        assertTrue(csp.contains("frame-ancestors 'none'"), csp);
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElseThrow());
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElseThrow());
        assertEquals("same-origin", response.headers().firstValue("Cross-Origin-Opener-Policy").orElseThrow());
        assertTrue(response.headers().firstValue("Permissions-Policy").orElseThrow().contains("camera=()"));
        assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Strict-Transport-Security").isEmpty(), "no HSTS without TLS");
    }

    @Test
    void aMatchingEtagGives304WithoutBody() throws Exception {
        String etag = get("/").headers().firstValue("ETag").orElseThrow();
        assertTrue(etag.matches("\"[A-Za-z0-9_-]{22}\""), etag);
        HttpResponse<byte[]> again = get("/", "If-None-Match", etag);
        assertEquals(304, again.statusCode());
        assertEquals(0, again.body().length);
        assertEquals(etag, again.headers().firstValue("ETag").orElseThrow());
        assertEquals(304, get("/", "If-None-Match", "\"other\", W/" + etag).statusCode());
        assertEquals(200, get("/", "If-None-Match", "\"other\"").statusCode());
    }

    @Test
    void hashedFilesAreImmutableAndTextIsGzipped() throws Exception {
        assertEquals("public, max-age=31536000, immutable",
                get("/_next/static/chunks/app.js").headers().firstValue("Cache-Control").orElseThrow());
        HttpResponse<byte[]> zipped = get("/", "Accept-Encoding", "gzip");
        assertEquals("gzip", zipped.headers().firstValue("Content-Encoding").orElseThrow());
        assertEquals("Accept-Encoding", zipped.headers().firstValue("Vary").orElseThrow());
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(zipped.body()))) {
            assertArrayEquals(PAGE.getBytes(StandardCharsets.UTF_8), in.readAllBytes());
        }
    }

    @Test
    void aFolderWithoutSlashIsRedirectedAndUnknownPathsGetThe404Page() throws Exception {
        HttpResponse<byte[]> redirect = get("/services");
        assertEquals(308, redirect.statusCode());
        assertEquals("/services/", redirect.headers().firstValue("Location").orElseThrow());
        assertEquals(200, get("/services/").statusCode());
        HttpResponse<byte[]> missing = get("/nothing-here");
        assertEquals(404, missing.statusCode());
        assertTrue(new String(missing.body(), StandardCharsets.UTF_8).contains("missing"));
        assertEquals("no-store", missing.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test
    void onlyGetAndHeadReachTheFiles() throws Exception {
        HttpResponse<String> post = client.send(HttpRequest.newBuilder(URI.create(base + "/"))
                .POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, post.statusCode());
        assertEquals("GET, HEAD", post.headers().firstValue("Allow").orElseThrow());
        HttpResponse<byte[]> head = client.send(HttpRequest.newBuilder(URI.create(base + "/"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, head.statusCode());
        assertEquals(0, head.body().length);
    }

    @Test
    void infoNamesTheVersion() throws Exception {
        HttpResponse<byte[]> info = get("/api/panel/info");
        assertEquals(200, info.statusCode());
        assertEquals("{\"name\":\"VectolynCloud\",\"version\":\"1.2.3\",\"frontend\":true}",
                new String(info.body(), StandardCharsets.UTF_8));
        assertEquals("no-store", info.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(404, get("/api/nothing").statusCode());
    }

    /** Raw HTTP, because HttpClient neither sends a foreign Host nor a target with dot segments. */
    private String raw(String requestLine, String host) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(base.substring(base.lastIndexOf(':') + 1)))) {
            OutputStream out = socket.getOutputStream();
            out.write((requestLine + "\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void aForeignHostIsRefusedAgainstDnsRebinding() throws IOException {
        assertTrue(raw("GET / HTTP/1.1", "attacker.example").startsWith("HTTP/1.1 421"));
        assertTrue(raw("GET / HTTP/1.1", "localhost:1234").startsWith("HTTP/1.1 200"));
        assertTrue(raw("GET / HTTP/1.1", "[::1]:1").startsWith("HTTP/1.1 200"));
    }

    @Test
    void targetsThatLeaveTheirFolderAreRefused() throws IOException {
        for (String target : new String[] {"/../index.html", "/a/./b", "/%2e%2e/x", "/a\\b", "//x", "/a//b"}) {
            assertTrue(raw("GET " + target + " HTTP/1.1", "localhost").startsWith("HTTP/1.1 400"), target);
        }
    }

    @Test
    void aPublicAddressNeedsTls() {
        IOException e = assertThrows(IOException.class, () -> PanelServer.builder(settings("0.0.0.0", null))
                .assets(WebAssets.of(files(), false)).build().start());
        assertTrue(e.getMessage().contains("TLS"), e.getMessage());
    }

    @Test
    void theAllowedHostsFollowBindAndPublicUrl() {
        assertEquals(java.util.Set.of("localhost", "127.0.0.1", "[::1]"),
                PanelServer.allowedHosts(settings("127.0.0.1", null)));
        assertTrue(PanelServer.allowedHosts(settings("0.0.0.0", null)).isEmpty(), "wildcard without public-url");
        assertTrue(PanelServer.allowedHosts(settings("0.0.0.0", "https://panel.example.org")).contains("panel.example.org"));
        assertTrue(PanelServer.allowedHosts(settings("::1", null)).contains("[::1]"));
        assertEquals(Optional.of("https://panel.example.org:8443"),
                PanelConfigLoader.origin("https://Panel.Example.org:8443/"));
    }
}
