package net.vectolyn.cloud.download.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;
import net.vectolyn.cloud.download.ChecksumMismatchException;
import net.vectolyn.cloud.download.HostNotAllowedException;
import net.vectolyn.cloud.download.SizeLimitExceededException;
import net.vectolyn.cloud.download.TofuMismatchException;
import net.vectolyn.cloud.download.hash.Checksum;
import net.vectolyn.cloud.download.hash.HashAlgorithm;
import net.vectolyn.cloud.download.hash.MultiDigest;
import net.vectolyn.cloud.download.http.FakeTransport;
import net.vectolyn.cloud.download.http.HostAllowlist;
import net.vectolyn.cloud.download.lock.LockedArtifact;
import net.vectolyn.cloud.download.lock.Trust;
import net.vectolyn.cloud.download.provider.ArtifactCoordinate;
import net.vectolyn.cloud.download.provider.ArtifactProvider;
import net.vectolyn.cloud.download.provider.ArtifactRequest;
import net.vectolyn.cloud.download.provider.ProviderRegistry;
import net.vectolyn.cloud.download.provider.ResolvedArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactDownloaderTest {

    private static final String URL = "https://cdn.example.org/server.jar";
    private static final byte[] CONTENT = "the real server jar".getBytes(StandardCharsets.UTF_8);
    private static final ArtifactCoordinate COORDINATE = new ArtifactCoordinate("test", "server", "1.0", "7");

    @TempDir
    Path home;

    private static final ArtifactProvider PROVIDER = new ArtifactProvider() {
        @Override
        public String id() {
            return "test";
        }

        @Override
        public HostAllowlist allowedHosts() {
            return HostAllowlist.of("cdn.example.org");
        }

        @Override
        public ResolvedArtifact resolve(ArtifactRequest request) {
            throw new UnsupportedOperationException();
        }
    };

    private ArtifactDownloader downloader(FakeTransport transport) {
        return new ArtifactDownloader(FakeTransport.client(transport), ProviderRegistry.of(List.of(PROVIDER)),
                new ArtifactCache(home.resolve("cache")));
    }

    private static Checksum hash(HashAlgorithm algorithm, byte[] data) {
        return MultiDigest.of(data, Set.of(algorithm)).get(algorithm);
    }

    private static ResolvedArtifact artifact(Checksum checksum) {
        return new ResolvedArtifact(COORDINATE, URI.create(URL), "server.jar", Optional.ofNullable(checksum),
                OptionalLong.empty(), OptionalInt.empty(), Map.of());
    }

    private List<Path> cacheFiles() throws IOException {
        Path dir = home.resolve("cache/artifacts");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.toList();
        }
    }

    @Test
    void storesTheVerifiedFileUnderItsSha256() throws Exception {
        FakeTransport transport = new FakeTransport().ok(URL, CONTENT);
        Checksum sha512 = hash(HashAlgorithm.SHA512, CONTENT);
        DownloadedArtifact result = downloader(transport).download(artifact(sha512), null);

        Checksum sha256 = hash(HashAlgorithm.SHA256, CONTENT);
        assertEquals(home.resolve("cache/artifacts").resolve(sha256.hex()), result.file());
        assertArrayEquals(CONTENT, Files.readAllBytes(result.file()));
        assertEquals(sha512, result.checksum());
        assertEquals(sha256, result.sha256());
        assertEquals(Trust.SOURCE, result.trust());
        assertFalse(result.fromCache());
        assertEquals(List.of(result.file()), cacheFiles(), "no .part file may remain");
    }

    @Test
    void wrongHashAbortsAndDeletesTheDownload() {
        FakeTransport transport = new FakeTransport().ok(URL, "tampered".getBytes(StandardCharsets.UTF_8));
        ChecksumMismatchException e = assertThrows(ChecksumMismatchException.class,
                () -> downloader(transport).download(artifact(hash(HashAlgorithm.SHA256, CONTENT)), null));
        assertTrue(e.getMessage().contains("deleted"), e.getMessage());
        assertDoesNotThrowIo(() -> assertEquals(List.of(), cacheFiles(), "neither .part nor final file may remain"));
    }

    @Test
    void wrongSha512AbortsAndDeletesTheDownload() throws Exception {
        FakeTransport transport = new FakeTransport().ok(URL, "tampered".getBytes(StandardCharsets.UTF_8));
        assertThrows(ChecksumMismatchException.class,
                () -> downloader(transport).download(artifact(hash(HashAlgorithm.SHA512, CONTENT)), null));
        assertEquals(List.of(), cacheFiles(), "neither .part nor final file may remain");
    }

    @Test
    void wrongSha1AbortsAndDeletesTheDownload() throws Exception {
        FakeTransport transport = new FakeTransport().ok(URL, "tampered".getBytes(StandardCharsets.UTF_8));
        assertThrows(ChecksumMismatchException.class,
                () -> downloader(transport).download(artifact(hash(HashAlgorithm.SHA1, CONTENT)), null));
        assertEquals(List.of(), cacheFiles(), "neither .part nor final file may remain");
    }

    @Test
    void tofuPinStaysBindingWhenTheSourceStartsPublishingAChecksum() throws Exception {
        ResolvedArtifact unchecked = artifact(null);
        LockedArtifact pinned = downloader(new FakeTransport().ok(URL, CONTENT)).download(unchecked, null)
                .toLocked(unchecked);
        for (Path file : cacheFiles()) {
            Files.delete(file);
        }
        // The source now serves other bytes and a checksum that fits them; the pin must still win.
        byte[] other = "something else".getBytes(StandardCharsets.UTF_8);
        ResolvedArtifact withChecksum = artifact(hash(HashAlgorithm.SHA512, other));
        assertThrows(TofuMismatchException.class,
                () -> downloader(new FakeTransport().ok(URL, other)).download(withChecksum, pinned));
        assertEquals(List.of(), cacheFiles());

        // Same bytes as pinned plus a fitting checksum: accepted, now verified by the source.
        ResolvedArtifact matching = artifact(hash(HashAlgorithm.SHA512, CONTENT));
        DownloadedArtifact result = downloader(new FakeTransport().ok(URL, CONTENT)).download(matching, pinned);
        assertEquals(Trust.SOURCE, result.trust());
        assertEquals(pinned.sha256(), result.sha256());
    }

    @Test
    void cachedValidFileIsNotDownloadedAgain() throws Exception {
        Checksum sha256 = hash(HashAlgorithm.SHA256, CONTENT);
        FakeTransport first = new FakeTransport().ok(URL, CONTENT);
        downloader(first).download(artifact(sha256), null);

        FakeTransport second = new FakeTransport();
        DownloadedArtifact again = downloader(second).download(artifact(sha256), null);
        assertTrue(again.fromCache());
        assertTrue(second.requests().isEmpty(), "a valid cached file must not be fetched again");
    }

    @Test
    void corruptCachedFileIsReplaced() throws Exception {
        Checksum sha256 = hash(HashAlgorithm.SHA256, CONTENT);
        Path cached = home.resolve("cache/artifacts").resolve(sha256.hex());
        Files.createDirectories(cached.getParent());
        Files.writeString(cached, "bit rot");
        FakeTransport transport = new FakeTransport().ok(URL, CONTENT);
        DownloadedArtifact result = downloader(transport).download(artifact(sha256), null);
        assertFalse(result.fromCache());
        assertArrayEquals(CONTENT, Files.readAllBytes(cached));
    }

    @Test
    void trustOnFirstUsePinsTheFirstDownload() throws Exception {
        FakeTransport transport = new FakeTransport().ok(URL, CONTENT);
        ResolvedArtifact resolved = artifact(null);
        DownloadedArtifact result = downloader(transport).download(resolved, null);
        assertEquals(Trust.TOFU, result.trust());
        LockedArtifact locked = result.toLocked(resolved);
        assertEquals(Trust.TOFU, locked.trust());
        assertEquals(hash(HashAlgorithm.SHA256, CONTENT), locked.checksum());
    }

    @Test
    void tofuMismatchLaterIsAnError() throws Exception {
        ResolvedArtifact resolved = artifact(null);
        LockedArtifact pinned = downloader(new FakeTransport().ok(URL, CONTENT)).download(resolved, null).toLocked(resolved);
        // Empty the cache so the file has to be fetched again, then the source serves other bytes.
        for (Path file : cacheFiles()) {
            Files.delete(file);
        }
        FakeTransport changed = new FakeTransport().ok(URL, "something else".getBytes(StandardCharsets.UTF_8));
        TofuMismatchException e = assertThrows(TofuMismatchException.class,
                () -> downloader(changed).download(resolved, pinned));
        assertTrue(e.getMessage().contains("software.lock.yml"), e.getMessage());
        assertEquals(List.of(), cacheFiles());
        assertThrows(TofuMismatchException.class, () -> downloader(changed).download(pinned));
    }

    @Test
    void sourcePublishingAnotherHashForTheSameBuildIsAnError() throws Exception {
        Checksum sha256 = hash(HashAlgorithm.SHA256, CONTENT);
        ResolvedArtifact resolved = artifact(sha256);
        LockedArtifact locked = downloader(new FakeTransport().ok(URL, CONTENT)).download(resolved, null).toLocked(resolved);
        Checksum other = hash(HashAlgorithm.SHA256, "x".getBytes(StandardCharsets.UTF_8));
        assertThrows(ChecksumMismatchException.class,
                () -> downloader(new FakeTransport()).download(artifact(other), locked));
    }

    @Test
    void lockReplayDownloadsAndVerifiesWithoutTheApi() throws Exception {
        Checksum sha512 = hash(HashAlgorithm.SHA512, CONTENT);
        ResolvedArtifact resolved = artifact(sha512);
        LockedArtifact locked = downloader(new FakeTransport().ok(URL, CONTENT)).download(resolved, null).toLocked(resolved);
        for (Path file : cacheFiles()) {
            Files.delete(file);
        }
        DownloadedArtifact replay = downloader(new FakeTransport().ok(URL, CONTENT)).download(locked);
        assertEquals(locked.sha256(), replay.sha256());
        assertThrows(ChecksumMismatchException.class, () -> {
            for (Path file : cacheFiles()) {
                Files.delete(file);
            }
            downloader(new FakeTransport().ok(URL, "evil".getBytes(StandardCharsets.UTF_8))).download(locked);
        });
    }

    @Test
    void downloadUrlMustBeOnTheProvidersHosts() {
        ResolvedArtifact foreign = new ResolvedArtifact(COORDINATE, URI.create("https://evil.example.net/server.jar"),
                "server.jar", Optional.of(hash(HashAlgorithm.SHA256, CONTENT)), OptionalLong.empty(), OptionalInt.empty(),
                Map.of());
        assertThrows(HostNotAllowedException.class, () -> downloader(new FakeTransport()).download(foreign, null));
    }

    @Test
    void announcedSizeIsTheLimit() {
        ResolvedArtifact sized = new ResolvedArtifact(COORDINATE, URI.create(URL), "server.jar",
                Optional.of(hash(HashAlgorithm.SHA256, CONTENT)), OptionalLong.of(5), OptionalInt.empty(), Map.of());
        assertThrows(SizeLimitExceededException.class,
                () -> downloader(new FakeTransport().ok(URL, CONTENT)).download(sized, null));
        assertDoesNotThrowIo(() -> assertEquals(List.of(), cacheFiles()));
    }

    @Test
    void copyToPlacesTheFile() throws Exception {
        Checksum sha256 = hash(HashAlgorithm.SHA256, CONTENT);
        downloader(new FakeTransport().ok(URL, CONTENT)).download(artifact(sha256), null);
        Path target = home.resolve("Proxy/runtime/velocity.jar");
        new ArtifactCache(home.resolve("cache")).copyTo(sha256, target);
        assertArrayEquals(CONTENT, Files.readAllBytes(target));
    }

    private interface IoRunnable {
        void run() throws IOException;
    }

    private static void assertDoesNotThrowIo(IoRunnable runnable) {
        try {
            runnable.run();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void removesOrphanedPartFilesBeforeADownload() throws Exception {
        Path dir = Files.createDirectories(home.resolve("cache/artifacts"));
        Path orphan = Files.write(dir.resolve("download-4711.part"), "half a jar".getBytes(StandardCharsets.UTF_8));
        DownloadedArtifact file = downloader(new FakeTransport().ok(URL, CONTENT))
                .download(artifact(hash(HashAlgorithm.SHA256, CONTENT)), null);
        assertFalse(Files.exists(orphan), "an aborted download of an earlier run is cleaned up");
        assertEquals(List.of(file.file()), cacheFiles());
    }
}
