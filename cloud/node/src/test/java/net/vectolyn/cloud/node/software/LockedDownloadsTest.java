package net.vectolyn.cloud.node.software;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.vectolyn.cloud.download.ChecksumMismatchException;
import net.vectolyn.cloud.download.cache.ArtifactCache;
import net.vectolyn.cloud.download.cache.ArtifactDownloader;
import net.vectolyn.cloud.download.catalog.ArtifactCatalog;
import net.vectolyn.cloud.download.catalog.CatalogEntry;
import net.vectolyn.cloud.download.hash.Checksum;
import net.vectolyn.cloud.download.hash.HashAlgorithm;
import net.vectolyn.cloud.download.http.DownloadClient;
import net.vectolyn.cloud.download.http.DownloadSettings;
import net.vectolyn.cloud.download.http.HostAllowlist;
import net.vectolyn.cloud.download.http.HttpTransport;
import net.vectolyn.cloud.download.http.TransportResponse;
import net.vectolyn.cloud.download.lock.SoftwareLockStore;
import net.vectolyn.cloud.download.provider.ArtifactCoordinate;
import net.vectolyn.cloud.download.provider.ArtifactProvider;
import net.vectolyn.cloud.download.provider.ArtifactRequest;
import net.vectolyn.cloud.download.provider.ProviderRegistry;
import net.vectolyn.cloud.download.provider.ResolvedArtifact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The real download flow on a fake source: no network, every byte checked. */
class LockedDownloadsTest {

    private static final String HOST = "downloads.example.org";

    @TempDir
    Path home;

    /** Files by URL; every request is counted, so a test can prove that nothing was asked. */
    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    final AtomicInteger requests = new AtomicInteger();
    final AtomicInteger resolves = new AtomicInteger();
    /** What the fake source answers: build and the checksum it publishes. */
    volatile String build = "100";
    volatile byte[] published;

    private ArtifactCatalog catalog;
    private LockedDownloads downloads;
    private SoftwareLockStore lock;

    private final HttpTransport transport = (uri, headers, timeout) -> {
        requests.incrementAndGet();
        byte[] body = files.get(uri.toString());
        if (body == null) {
            return new TransportResponse(404, Map.of(), new ByteArrayInputStream(new byte[0]));
        }
        return new TransportResponse(200, Map.of("Content-Length", List.of(Integer.toString(body.length))),
                new ByteArrayInputStream(body));
    };

    private final ArtifactProvider provider = new ArtifactProvider() {
        @Override
        public String id() {
            return "fake";
        }

        @Override
        public HostAllowlist allowedHosts() {
            return HostAllowlist.of(HOST);
        }

        @Override
        public ResolvedArtifact resolve(ArtifactRequest request) {
            resolves.incrementAndGet();
            String version = request.versionOpt().orElse("9.9");
            String url = "https://" + HOST + "/" + request.project() + "/" + version + "/" + build + ".jar";
            byte[] content = (request.project() + " " + version + " build " + build).getBytes(StandardCharsets.UTF_8);
            files.put(url, content);
            byte[] claimed = published != null ? published : content;
            return new ResolvedArtifact(new ArtifactCoordinate("fake", request.project(), version, build), URI.create(url),
                    request.project() + "-" + version + "-" + build + ".jar",
                    Optional.of(Checksum.of(HashAlgorithm.SHA256, sha256(claimed))), OptionalLong.of(content.length),
                    OptionalInt.of(25), Map.of());
        }
    };

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        catalog = ArtifactCatalog.parse("""
                schema-version: 1
                artifacts:
                  - id: server
                    kind: server
                    type: standard
                    provider: fake
                    project: server
                    game-version: version
                  - id: proxy
                    kind: server
                    type: standard
                    provider: fake
                    project: proxy
                    game-version: none
                    version: "4.3.1"
                """);
        lock = SoftwareLockStore.inHome(home);
        downloads = newDownloads();
    }

    private LockedDownloads newDownloads() {
        DownloadClient client = new DownloadClient(transport, DownloadSettings.defaults());
        ProviderRegistry providers = ProviderRegistry.of(List.of(provider));
        ArtifactCache cache = new ArtifactCache(home.resolve("cache"));
        return new LockedDownloads(providers, new ArtifactDownloader(client, providers, cache), cache, lock);
    }

    private CatalogEntry entry(String id) {
        return catalog.get(id).orElseThrow();
    }

    @Test
    void theFirstStartResolvesDownloadsAndPinsLaterStartsReplayWithoutTheNetwork() throws Exception {
        ArtifactSource.Fetched first = downloads.fetch("server-26.3", entry("server"), "26.3");
        assertEquals(1, resolves.get());
        assertEquals("server-26.3-100.jar", first.fileName());
        assertEquals(OptionalInt.of(25), first.javaMinimum());
        assertTrue(lock.get("server-26.3").isPresent(), "pinned in software.lock.yml");
        Path target = home.resolve("runtime/paper.jar");
        downloads.copy(first, target);
        assertArrayEquals("server 26.3 build 100".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));

        // A newer build appears; the pinned one stays, and nothing is asked or downloaded again.
        build = "101";
        int before = requests.get();
        LockedDownloads restarted = newDownloads();
        ArtifactSource.Fetched again = restarted.fetch("server-26.3", entry("server"), "26.3");
        assertEquals(first.sha256(), again.sha256());
        assertEquals(1, resolves.get(), "no API request for a pinned entry");
        assertEquals(before, requests.get(), "no request at all while the file is cached");
        assertEquals(OptionalInt.of(25), again.javaMinimum(), "the minimum survives without the network");
    }

    @Test
    void anotherMinecraftVersionOrPinIsResolvedAnew() throws Exception {
        downloads.fetch("server-26.3", entry("server"), "26.3");
        // Same lock name, but the group moved to another version: the old pin does not fit.
        ArtifactSource.Fetched moved = downloads.fetch("server-26.3", entry("server"), "26.4");
        assertEquals(2, resolves.get());
        assertEquals("server-26.4-100.jar", moved.fileName());

        ArtifactSource.Fetched proxy = downloads.fetch("proxy", entry("proxy"), null);
        assertEquals("proxy-4.3.1-100.jar", proxy.fileName(), "the catalog's pin is the version asked for");
        assertTrue(LockedDownloads.fits(lock.get("proxy").orElseThrow().coordinate(), entry("proxy"), null));
        assertFalse(LockedDownloads.fits(new ArtifactCoordinate("fake", "proxy", "4.4.0", "1"), entry("proxy"), null),
                "a pin from another release line does not fit");
    }

    @Test
    void aWrongChecksumFailsAndNothingIsPinned() throws Exception {
        published = "something else".getBytes(StandardCharsets.UTF_8);
        assertThrows(ChecksumMismatchException.class, () -> downloads.fetch("server-26.3", entry("server"), "26.3"));
        assertTrue(lock.get("server-26.3").isEmpty(), "a file that failed its check is never pinned");
        try (var parts = Files.list(home.resolve("cache/artifacts"))) {
            assertTrue(parts.noneMatch(p -> p.getFileName().toString().endsWith(".part")), "no part file stays");
        }
    }

    @Test
    void aCacheFileChangedOnDiskIsNotCopied() throws Exception {
        ArtifactSource.Fetched fetched = downloads.fetch("server-26.3", entry("server"), "26.3");
        Path cached = home.resolve("cache/artifacts").resolve(fetched.sha256());
        Files.writeString(cached, "tampered");
        Path target = home.resolve("runtime/paper.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "old");
        assertThrows(ChecksumMismatchException.class, () -> downloads.copy(fetched, target));
        assertEquals("old", Files.readString(target), "the server keeps its old jar");
    }
}
