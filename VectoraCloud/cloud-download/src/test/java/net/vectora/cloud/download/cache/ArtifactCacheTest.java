package net.vectora.cloud.download.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.vectora.cloud.download.ChecksumMismatchException;
import net.vectora.cloud.download.hash.Checksum;
import net.vectora.cloud.download.hash.HashAlgorithm;
import net.vectora.cloud.download.hash.MultiDigest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactCacheTest {

    private static final byte[] CONTENT = "server jar".getBytes(StandardCharsets.UTF_8);
    private static final Checksum SHA256 = MultiDigest.of(CONTENT, List.of()).get(HashAlgorithm.SHA256);

    @TempDir
    Path home;

    private ArtifactCache cacheWithFile(byte[] stored) throws Exception {
        ArtifactCache cache = new ArtifactCache(home.resolve("cache"));
        Files.createDirectories(cache.directory());
        Files.write(cache.pathFor(SHA256), stored);
        return cache;
    }

    @Test
    void copyToPlacesAVerifiedFile() throws Exception {
        ArtifactCache cache = cacheWithFile(CONTENT);
        Path target = home.resolve("runtime/lobby/paper.jar");
        cache.copyTo(SHA256, target);
        assertArrayEquals(CONTENT, Files.readAllBytes(target));
        try (var files = Files.list(target.getParent())) {
            assertEquals(List.of(target), files.toList(), "no temporary file left behind");
        }
    }

    @Test
    void copyToChecksTheHashAgainAndDropsACorruptedCacheFile() throws Exception {
        ArtifactCache cache = cacheWithFile("tampered jar".getBytes(StandardCharsets.UTF_8));
        Path target = home.resolve("runtime/lobby/paper.jar");
        Files.createDirectories(target.getParent());
        Files.write(target, "old jar".getBytes(StandardCharsets.UTF_8));

        ChecksumMismatchException e = assertThrows(ChecksumMismatchException.class, () -> cache.copyTo(SHA256, target));
        assertTrue(e.getMessage().contains(SHA256.hex()), e.getMessage());
        assertArrayEquals("old jar".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target),
                "the existing file stays untouched");
        assertFalse(Files.exists(cache.pathFor(SHA256)), "the corrupted cache file is deleted");
        try (var files = Files.list(target.getParent())) {
            assertEquals(List.of(target), files.toList(), "no temporary file left behind");
        }
    }

    @Test
    void copyToFailsForAMissingCacheFile() {
        ArtifactCache cache = new ArtifactCache(home.resolve("cache"));
        assertThrows(java.io.IOException.class, () -> cache.copyTo(SHA256, home.resolve("runtime/paper.jar")));
    }

    @Test
    void removesOrphanedPartFilesButNotActiveOnes() throws Exception {
        ArtifactCache cache = new ArtifactCache(home.resolve("cache"));
        Files.createDirectories(cache.directory());
        Path orphan = Files.write(cache.directory().resolve("download-123" + ArtifactCache.PART_SUFFIX), CONTENT);
        Path stored = Files.write(cache.directory().resolve(SHA256.hex()), CONTENT);
        Path active = cache.newPartFile();

        // A second cache object on the same directory, as after a restart or in another component.
        int removed = new ArtifactCache(home.resolve("cache")).removeStalePartFiles();

        assertEquals(1, removed);
        assertFalse(Files.exists(orphan), "an orphaned .part file from an earlier run is removed");
        assertTrue(Files.exists(active), "a .part file of a running download stays");
        assertTrue(Files.exists(stored), "verified files stay");

        cache.discard(active);
        assertFalse(Files.exists(active));
    }

    @Test
    void aCommittedPartFileIsNoLongerTracked() throws Exception {
        ArtifactCache cache = new ArtifactCache(home.resolve("cache"));
        Path part = cache.newPartFile();
        Files.write(part, CONTENT);
        Path stored = cache.commit(part, SHA256);
        assertEquals(cache.pathFor(SHA256), stored);
        // The same name can come up again (temp file names are reused); it must count as orphaned then.
        Files.write(part, CONTENT);
        assertEquals(1, cache.removeStalePartFiles());
    }

    @Test
    void cleanupWithoutDirectoryDoesNothing() throws Exception {
        assertEquals(0, new ArtifactCache(home.resolve("missing")).removeStalePartFiles());
    }
}
