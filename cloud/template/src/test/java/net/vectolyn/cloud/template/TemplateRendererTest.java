package net.vectolyn.cloud.template;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateRendererTest {

    private static final PlaceholderValues VALUES = PlaceholderValues.of(Map.of(
            "VECTOLYN_SERVER_PORT", "25566",
            "VECTOLYN_XMX", "768M"));

    @TempDir
    Path dir;

    private Path template() throws IOException {
        return Files.createDirectories(dir.resolve("template"));
    }

    private Path runtime() {
        return dir.resolve("runtime");
    }

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content, UTF_8);
    }

    @Test
    void rendersTextFilesAndCreatesFolders() throws Exception {
        Path template = template();
        write(template.resolve("server.properties"), "server-port={{VECTOLYN_SERVER_PORT}}\n");
        write(template.resolve("plugins/LuckPerms/config.yml"), "port: {{VECTOLYN_SERVER_PORT}}\n");
        write(template.resolve("start.bat"), "java -Xmx{{VECTOLYN_XMX}}\r\n");

        RenderReport report = TemplateRenderer.render(template, runtime(), VALUES);

        assertEquals("server-port=25566\n", Files.readString(runtime().resolve("server.properties")));
        assertEquals("port: 25566\n", Files.readString(runtime().resolve("plugins/LuckPerms/config.yml")));
        assertEquals("java -Xmx768M\r\n", Files.readString(runtime().resolve("start.bat")));
        assertEquals(List.of("plugins/LuckPerms/config.yml", "server.properties", "start.bat"), report.rendered());
        assertEquals(List.of(), report.copied());
    }

    @Test
    void extensionsAreMatchedWithoutCase() throws Exception {
        write(template().resolve("CONFIG.YML"), "a: {{VECTOLYN_SERVER_PORT}}\n");
        TemplateRenderer.render(template(), runtime(), VALUES);
        assertEquals("a: 25566\n", Files.readString(runtime().resolve("CONFIG.YML")));
    }

    @Test
    void otherFilesAreCopiedByteForByte() throws Exception {
        byte[] binary = {0, (byte) 0xFF, '{', '{', 'V', 'E', 'C', 'T', 'O', 'R', 'A', '_', 'X', '}', '}', (byte) 0xC3};
        Files.write(template().resolve("icon.png"), binary);
        Files.write(template().resolve("server-icon"), binary);
        Files.write(template().resolve("level.dat"), binary);

        RenderReport report = TemplateRenderer.render(template(), runtime(), VALUES);

        assertArrayEquals(binary, Files.readAllBytes(runtime().resolve("icon.png")));
        assertArrayEquals(binary, Files.readAllBytes(runtime().resolve("server-icon")));
        assertArrayEquals(binary, Files.readAllBytes(runtime().resolve("level.dat")));
        assertEquals(List.of("icon.png", "level.dat", "server-icon"), report.copied());
    }

    @Test
    void markdownFilesAreNeverCopied() throws Exception {
        write(template().resolve("README.md"), "Uses `{{VECTOLYN_DB_PASSWORD}}` in config.yml\n");
        write(template().resolve("docs/notes.md"), "port {{VECTOLYN_SERVER_PORT}}\n");
        write(template().resolve("plugins/x/CHANGES.MD"), "{{VECTOLYN_DB_PASSWORD}}\n");
        write(template().resolve("a.txt"), "a\n");

        RenderReport report = TemplateRenderer.render(template(), runtime(), VALUES);

        assertFalse(Files.exists(runtime().resolve("README.md")));
        assertFalse(Files.exists(runtime().resolve("docs")));
        assertFalse(Files.exists(runtime().resolve("plugins")));
        assertEquals(List.of("README.md", "docs/notes.md", "plugins/x/CHANGES.MD"), report.skipped());
        assertEquals(List.of("a.txt"), report.rendered());
    }

    @Test
    void templateFilesAreOverwrittenAndRuntimeFilesStay() throws Exception {
        write(template().resolve("server.properties"), "server-port={{VECTOLYN_SERVER_PORT}}\n");
        write(template().resolve("world/readme.txt"), "template\n");
        Path runtime = Files.createDirectories(runtime());
        write(runtime.resolve("server.properties"), "server-port=1\nold-and-longer=true\n");
        byte[] region = {1, 2, 3};
        Files.createDirectories(runtime.resolve("world/region"));
        Files.write(runtime.resolve("world/region/r.0.0.mca"), region);
        write(runtime.resolve("logs/latest.log"), "log\n");
        Files.write(runtime.resolve("paper.jar"), region);

        TemplateRenderer.render(template(), runtime, VALUES);

        assertEquals("server-port=25566\n", Files.readString(runtime.resolve("server.properties")));
        assertEquals("template\n", Files.readString(runtime.resolve("world/readme.txt")));
        assertArrayEquals(region, Files.readAllBytes(runtime.resolve("world/region/r.0.0.mca")));
        assertEquals("log\n", Files.readString(runtime.resolve("logs/latest.log")));
        assertArrayEquals(region, Files.readAllBytes(runtime.resolve("paper.jar")));
    }

    @Test
    void unknownPlaceholderNamesTheFileAndLineAndWritesNothing() throws Exception {
        write(template().resolve("a.yml"), "a: {{VECTOLYN_SERVER_PORT}}\n");
        write(template().resolve("plugins/x/config.yml"), "a: 1\nb: {{VECTOLYN_UNKNOWN}}\n");

        TemplateException e = assertThrows(TemplateException.class,
                () -> TemplateRenderer.render(template(), runtime(), VALUES));

        assertEquals("plugins/x/config.yml", e.file());
        assertEquals(2, e.line());
        assertFalse(Files.exists(runtime().resolve("a.yml")), "a broken template must not leave a half-rendered runtime");
    }

    @Test
    void missingTemplateFolderIsAnError() {
        assertThrows(IOException.class, () -> TemplateRenderer.render(dir.resolve("nope"), runtime(), VALUES));
    }

    @Test
    void symbolicLinksInTheTemplateAreNotFollowed() throws Exception {
        Path outside = write(dir.resolve("outside/secret.txt"), "secret\n");
        Path template = template();
        write(template.resolve("a.txt"), "a\n");
        assumeTrue(trySymlink(template.resolve("link.txt"), outside));
        assumeTrue(trySymlink(template.resolve("linkdir"), outside.getParent()));

        RenderReport report = TemplateRenderer.render(template, runtime(), VALUES);

        assertFalse(Files.exists(runtime().resolve("link.txt"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(runtime().resolve("linkdir"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals(List.of("link.txt", "linkdir"), report.skipped());
        assertEquals(List.of("a.txt"), report.rendered());
    }

    @Test
    void symbolicLinkInTheRuntimeIsNotWrittenThrough() throws Exception {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        write(template().resolve("plugins/x/config.yml"), "a: 1\n");
        Path runtime = Files.createDirectories(runtime());
        assumeTrue(trySymlink(runtime.resolve("plugins"), outside));

        TemplateException e = assertThrows(TemplateException.class,
                () -> TemplateRenderer.render(template(), runtime, VALUES));

        assertTrue(e.getMessage().contains("symbolic link"), e.getMessage());
        assertFalse(Files.exists(outside.resolve("x")));
    }

    @Test
    void symbolicLinkAsTargetFileIsNotWrittenThrough() throws Exception {
        Path outside = write(dir.resolve("outside/target.yml"), "untouched\n");
        write(template().resolve("config.yml"), "a: 1\n");
        Path runtime = Files.createDirectories(runtime());
        assumeTrue(trySymlink(runtime.resolve("config.yml"), outside));

        assertThrows(TemplateException.class, () -> TemplateRenderer.render(template(), runtime, VALUES));
        assertEquals("untouched\n", Files.readString(outside));
    }

    @Test
    void textFileTypes() {
        for (String name : List.of("a.yml", "a.yaml", "a.toml", "a.properties", "a.json", "a.conf", "a.txt",
                "a.cfg", "a.ini", "a.sql", "start.bat", "start.cmd", "x.Properties")) {
            assertTrue(TemplateRenderer.isRenderedFile(name), name);
        }
        for (String name : List.of("paper.jar", "icon.png", "level.dat", "key.pem", "forwarding.secret", "yml", ".yml.bak", "a.md",
                "start.sh", "noext")) {
            assertFalse(TemplateRenderer.isRenderedFile(name), name);
        }
    }

    @Test
    void junctionsInTheTemplateAreNotFollowed() throws Exception {
        assumeTrue(isWindows(), "junctions exist on Windows only");
        Path outside = write(dir.resolve("outside/secret.txt"), "secret {{VECTOLYN_SERVER_PORT}}\n");
        Path template = template();
        write(template.resolve("a.txt"), "a\n");
        Path link = template.resolve("linkdir");
        junction(link, outside.getParent());
        try {
            RenderReport report = TemplateRenderer.render(template, runtime(), VALUES);

            assertFalse(Files.exists(runtime().resolve("linkdir"), LinkOption.NOFOLLOW_LINKS));
            assertEquals(List.of("linkdir"), report.skipped());
            assertEquals(List.of("a.txt"), report.rendered());
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void junctionInTheRuntimeIsNotWrittenThrough() throws Exception {
        assumeTrue(isWindows(), "junctions exist on Windows only");
        Path outside = Files.createDirectories(dir.resolve("outside"));
        write(template().resolve("plugins/x/config.yml"), "a: 1\n");
        Path runtime = Files.createDirectories(runtime());
        Path link = runtime.resolve("plugins");
        junction(link, outside);
        try {
            TemplateException e = assertThrows(TemplateException.class,
                    () -> TemplateRenderer.render(template(), runtime, VALUES));

            assertTrue(e.getMessage().contains("junction"), e.getMessage());
            assertFalse(Files.exists(outside.resolve("x")));
        } finally {
            Files.delete(link);
        }
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** Creates a Windows junction: a folder reparse point that Java does not report as a symbolic link. */
    static void junction(Path link, Path target) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
        assertEquals(0, process.waitFor(), output);
        BasicFileAttributes attributes = Files.readAttributes(link, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        assertFalse(attributes.isSymbolicLink(), "the test needs a junction, not a symbolic link");
    }

    static boolean trySymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            // Windows without developer mode or administrator rights cannot create symbolic links.
            return false;
        }
    }
}
