package net.vectolyn.cloud.launcher.libs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryExtractorTest {

    @TempDir
    Path home;

    private final Map<String, byte[]> embedded = new HashMap<>();

    private LibraryExtractor.Source source() {
        return name -> embedded.containsKey(name) ? new ByteArrayInputStream(embedded.get(name)) : null;
    }

    private Path libs() {
        return home.resolve("storage").resolve("libs");
    }

    private static String sha256(byte[] data) throws IOException {
        return LibraryExtractor.sha256(new ByteArrayInputStream(data));
    }

    private LibraryIndex index(String... lines) throws LibraryException {
        return LibraryIndex.parse(String.join("\n", lines));
    }

    @Test
    void unpacksAndVerifies() throws Exception {
        byte[] node = "node classes".getBytes(StandardCharsets.UTF_8);
        byte[] netty = "netty classes".getBytes(StandardCharsets.UTF_8);
        embedded.put("cloud-node-0.1.0.jar", node);
        embedded.put("netty-transport-4.2.19.Final.jar", netty);
        LibraryIndex index = index(
                "# header",
                sha256(node) + "  cloud-node-0.1.0.jar",
                sha256(netty) + "  netty-transport-4.2.19.Final.jar");

        List<Path> jars = new LibraryExtractor(source(), libs()).extract(index);

        assertEquals(2, jars.size());
        assertArrayEquals(node, Files.readAllBytes(jars.get(0)));
        assertArrayEquals(netty, Files.readAllBytes(jars.get(1)));
        assertEquals(libs().toAbsolutePath().normalize(), jars.get(0).getParent());
    }

    @Test
    void wrongHashAbortsAndLeavesNothingBehind() throws Exception {
        embedded.put("cloud-node.jar", "tampered".getBytes(StandardCharsets.UTF_8));
        LibraryIndex index = index(sha256("original".getBytes(StandardCharsets.UTF_8)) + "  cloud-node.jar");

        LibraryException e = assertThrows(LibraryException.class,
                () -> new LibraryExtractor(source(), libs()).extract(index));

        assertEquals(LibraryException.Kind.HASH_MISMATCH, e.kind());
        assertEquals("cloud-node.jar", e.subject());
        try (Stream<Path> files = Files.list(libs())) {
            assertEquals(List.of(), files.toList(), "neither the jar nor a temp file may remain");
        }
    }

    @Test
    void tamperedCacheIsReplaced() throws Exception {
        byte[] good = "good".getBytes(StandardCharsets.UTF_8);
        embedded.put("a.jar", good);
        Files.createDirectories(libs());
        Files.writeString(libs().resolve("a.jar"), "evil");

        List<Path> jars = new LibraryExtractor(source(), libs()).extract(index(sha256(good) + "  a.jar"));

        assertArrayEquals(good, Files.readAllBytes(jars.get(0)));
    }

    @Test
    void validCacheIsReused() throws Exception {
        byte[] good = "good".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(libs());
        Files.write(libs().resolve("a.jar"), good);
        // Not embedded at all: it must come from the cache.
        List<Path> jars = new LibraryExtractor(source(), libs()).extract(index(sha256(good) + "  a.jar"));
        assertArrayEquals(good, Files.readAllBytes(jars.get(0)));
    }

    @Test
    void missingEmbeddedJar() throws Exception {
        LibraryException e = assertThrows(LibraryException.class,
                () -> new LibraryExtractor(source(), libs()).extract(index("a".repeat(64) + "  gone.jar")));
        assertEquals(LibraryException.Kind.MISSING, e.kind());
    }

    @Test
    void staleJarsAreRemoved() throws Exception {
        byte[] current = "v2".getBytes(StandardCharsets.UTF_8);
        embedded.put("lib-2.jar", current);
        Files.createDirectories(libs());
        Files.writeString(libs().resolve("lib-1.jar"), "v1");
        Files.writeString(libs().resolve("notes.txt"), "kept");

        new LibraryExtractor(source(), libs()).extract(index(sha256(current) + "  lib-2.jar"));

        assertFalse(Files.exists(libs().resolve("lib-1.jar")));
        assertTrue(Files.exists(libs().resolve("lib-2.jar")));
        assertTrue(Files.exists(libs().resolve("notes.txt")), "only jars are cleaned up");
    }

    @Test
    void zipSlipNamesAreRejectedByTheIndex() {
        for (String name : List.of("../evil.jar", "..\\evil.jar", "sub/evil.jar", "/etc/evil.jar",
                "C:\\evil.jar", "..jar", "evil.jar/..", ".hidden.jar", "evil.sh")) {
            LibraryException e = assertThrows(LibraryException.class,
                    () -> index("0".repeat(64) + "  " + name), name);
            assertEquals(LibraryException.Kind.UNSAFE_NAME, e.kind(), name);
        }
    }

    @Test
    void zipSlipNamesAreRejectedByTheExtractor() {
        // Second line of defence, independent of the index parser.
        LibraryExtractor extractor = new LibraryExtractor(source(), libs());
        for (String name : List.of("../evil.jar", "../../home/user/.bashrc", "a/../../evil.jar")) {
            LibraryException e = assertThrows(LibraryException.class, () -> extractor.resolveInside(name), name);
            assertEquals(LibraryException.Kind.UNSAFE_NAME, e.kind(), name);
        }
        assertFalse(Files.exists(home.resolve("evil.jar")));
    }

    @Test
    void directChildCheckRejectsParentAndSubdirectory(@TempDir Path dir) throws Exception {
        // The check behind isSafeName, tested on its own: resolveInside never reaches it with
        // these names because the name check rejects them first.
        Path base = dir.toAbsolutePath().normalize();
        for (Path target : List.of(base.resolve("../x.jar"), base.resolve("a/b.jar"), base.resolve("a/../../x.jar"))) {
            LibraryException e = assertThrows(LibraryException.class,
                    () -> LibraryExtractor.requireDirectChild(base, target), target.toString());
            assertEquals(LibraryException.Kind.UNSAFE_NAME, e.kind(), target.toString());
        }
        assertEquals(base.resolve("x.jar"), LibraryExtractor.requireDirectChild(base, base.resolve("a/../x.jar")));
    }

    @Test
    void malformedIndexLines() {
        for (String line : List.of("abc  a.jar", "0".repeat(63) + "  a.jar", "0".repeat(64) + "a.jar", "0".repeat(64))) {
            LibraryException e = assertThrows(LibraryException.class, () -> index(line), line);
            assertEquals(LibraryException.Kind.INDEX_INVALID, e.kind(), line);
        }
    }

    @Test
    void duplicateNamesInIndex() {
        LibraryException e = assertThrows(LibraryException.class,
                () -> index("0".repeat(64) + "  a.jar", "1".repeat(64) + "  A.jar"));
        assertEquals(LibraryException.Kind.INDEX_INVALID, e.kind());
    }

    @Test
    void indexAcceptsSha256sumBinaryMarkerAndUpperCaseHex() throws Exception {
        LibraryIndex parsed = index("ABCDEF" + "0".repeat(58) + " *a.jar", "", "# comment");
        assertEquals(List.of(new LibraryEntry("a.jar", "abcdef" + "0".repeat(58))), parsed.entries());
    }
}
