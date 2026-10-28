package net.vectora.cloud.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import net.vectora.cloud.launcher.libs.LibraryExtractor;
import net.vectora.cloud.launcher.libs.LibraryIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeStarterTest {

    /** Stand-in for net.vectora.cloud.node.NodeMain. */
    public static final class FakeNode {
        static Map<String, String> received;
        static ClassLoader contextLoader;

        private FakeNode() {
        }

        public static void start(Map<String, String> settings) {
            received = settings;
            contextLoader = Thread.currentThread().getContextClassLoader();
        }
    }

    /** Entry class whose start method throws. */
    public static final class FailingNode {
        private FailingNode() {
        }

        public static void start(Map<String, String> settings) {
            throw new IllegalStateException("database unreachable");
        }
    }

    /** Entry class with an instance method instead of a static one. */
    public static final class InstanceNode {
        public void start(Map<String, String> settings) {
            // Not static, so not a valid entry point.
        }
    }

    @Test
    void callsStartWithSettingsAndContextLoader() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        NodeStarter.start(loader, FakeNode.class.getName(), Map.of("home", "/srv/cloud"));
        assertEquals(Map.of("home", "/srv/cloud"), FakeNode.received);
        assertSame(loader, FakeNode.contextLoader);
    }

    @Test
    void missingEntryClass() {
        NodeStartException e = assertThrows(NodeStartException.class,
                () -> NodeStarter.start(getClass().getClassLoader(), "net.vectora.cloud.node.DoesNotExist", Map.of()));
        assertEquals(NodeStartException.Kind.MISSING, e.kind());
    }

    @Test
    void entryWithoutStaticStart() {
        assertEquals(NodeStartException.Kind.INVALID_ENTRY, assertThrows(NodeStartException.class,
                () -> NodeStarter.start(getClass().getClassLoader(), InstanceNode.class.getName(), Map.of())).kind());
        assertEquals(NodeStartException.Kind.INVALID_ENTRY, assertThrows(NodeStartException.class,
                () -> NodeStarter.start(getClass().getClassLoader(), String.class.getName(), Map.of())).kind());
    }

    @Test
    void failureOfStartIsUnwrapped() {
        NodeStartException e = assertThrows(NodeStartException.class,
                () -> NodeStarter.start(getClass().getClassLoader(), FailingNode.class.getName(), Map.of()));
        assertEquals(NodeStartException.Kind.FAILED, e.kind());
        assertEquals("database unreachable", e.getCause().getMessage());
    }

    @Test
    void withoutIndexTheLauncherLoaderIsUsed(@TempDir Path dir) throws Exception {
        ClassLoader noIndex = new ClassLoader(null) {
        };
        assertSame(noIndex, NodeStarter.classLoader(noIndex, dir));
    }

    @Test
    void classLoaderSeesTheUnpackedJars(@TempDir Path dir) throws Exception {
        byte[] jar = jarWith("vectora-test/marker.txt", "from the embedded jar");
        String hash = LibraryExtractor.sha256(new ByteArrayInputStream(jar));
        LibraryIndex index = LibraryIndex.parse(hash + "  cloud-node-test.jar\n");
        LibraryExtractor.Source source = name -> name.equals("cloud-node-test.jar") ? new ByteArrayInputStream(jar) : null;

        ClassLoader loader = NodeStarter.classLoader(index, source, dir.resolve("libs"), getClass().getClassLoader());

        // Closed here only so that Windows releases the jar and @TempDir can delete it.
        try (URLClassLoader urlLoader = assertInstanceOf(URLClassLoader.class, loader)) {
            assertEquals("vectora-node", urlLoader.getName());
            try (InputStream in = urlLoader.getResourceAsStream("vectora-test/marker.txt")) {
                assertNotNull(in);
                assertEquals("from the embedded jar", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    private static byte[] jarWith(String entry, String content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry(entry));
            jar.write(content.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }
}
