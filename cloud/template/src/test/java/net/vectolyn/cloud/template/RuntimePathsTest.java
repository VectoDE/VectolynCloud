package net.vectolyn.cloud.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimePathsTest {

    @TempDir
    Path runtime;

    @Test
    void resolvesInsideTheRuntime() throws Exception {
        assertEquals(runtime.resolve("plugins").resolve("floodgate").resolve("key.pem").toAbsolutePath(),
                RuntimePaths.resolve(runtime, "plugins/floodgate/key.pem"));
    }

    @Test
    void rejectsEveryPathThatLeavesTheRuntime() {
        for (String bad : List.of("../evil.txt", "a/../../evil.txt", "a/../..", "..", "/etc/passwd", "\\evil",
                "C:/evil.txt", "C:evil.txt", "a\\..\\..\\evil", "", ".", "a/./b", "a//b", "a/", "nul\0.txt")) {
            assertThrows(TemplateException.class, () -> RuntimePaths.resolve(runtime, bad), bad);
        }
    }

    @Test
    void writeRefusesSymbolicLinksOnTheWay() throws Exception {
        Path outside = Files.createTempDirectory("vectolyn-outside");
        try {
            assumeTrue(TemplateRendererTest.trySymlink(runtime.resolve("plugins"), outside));
            assertThrows(TemplateException.class,
                    () -> RuntimePaths.write(runtime, "plugins/floodgate/key.pem", new byte[16], true));
            assertFalse(Files.exists(outside.resolve("floodgate")));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void writeRefusesJunctionsOnTheWay() throws Exception {
        assumeTrue(TemplateRendererTest.isWindows(), "junctions exist on Windows only");
        Path root = Files.createDirectories(runtime.resolve("runtime"));
        Path outside = Files.createDirectories(runtime.resolve("outside"));
        Path link = root.resolve("plugins");
        TemplateRendererTest.junction(link, outside);
        try {
            assertThrows(TemplateException.class, () -> RuntimePaths.check(root, "plugins/floodgate/key.pem"));
            assertThrows(TemplateException.class,
                    () -> RuntimePaths.write(root, "plugins/floodgate/key.pem", new byte[16], true));
            assertFalse(Files.exists(outside.resolve("floodgate")));
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void writeReplacesAnExistingFile() throws Exception {
        Files.writeString(runtime.resolve("eula.txt"), "eula=false and some more text\n");
        RuntimePaths.write(runtime, "eula.txt", "eula=true\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
        assertEquals("eula=true\n", Files.readString(runtime.resolve("eula.txt")));
    }
}
