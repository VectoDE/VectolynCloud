package net.vectora.cloud.template;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateRendererTest {

    private static final PlaceholderValues VALUES = PlaceholderValues.of(Map.of(
            "VECTORA_SERVER_PORT", "25566",
            "VECTORA_XMX", "768M"));

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
        write(template.resolve("server.properties"), "server-port={{VECTORA_SERVER_PORT}}\n");
        write(template.resolve("plugins/LuckPerms/config.yml"), "port: {{VECTORA_SERVER_PORT}}\n");
        write(template.resolve("start.bat"), "java -Xmx{{VECTORA_XMX}}\r\n");

        RenderReport report = TemplateRenderer.render(template, runtime(), VALUES);

        assertEquals("server-port=25566\n", Files.readString(runtime().resolve("server.properties")));
        assertEquals("port: 25566\n", Files.readString(runtime().resolve("plugins/LuckPerms/config.yml")));
        assertEquals("java -Xmx768M\r\n", Files.readString(runtime().resolve("start.bat")));
        assertEquals(List.of("plugins/LuckPerms/config.yml", "server.properties", "start.bat"), report.rendered());
        assertEquals(List.of(), report.copied());
    }

    @Test
    void extensionsAreMatchedWithoutCase() throws Exception {
        write(template().resolve("CONFIG.YML"), "a: {{VECTORA_SERVER_PORT}}\n");
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
    void templateReadmeIsNotCopied() throws Exception {
        write(template().resolve("README.md"), "Uses `{{VECTORA_DB_PASSWORD}}` in config.yml\n");
        write(template().resolve("docs/notes.md"), "port {{VECTORA_SERVER_PORT}}\n");

        RenderReport report = TemplateRenderer.render(template(), runtime(), VALUES);

        assertFalse(Files.exists(runtime().resolve("README.md")));
        assertEquals("port 25566\n", Files.readString(runtime().resolve("docs/notes.md")));
        assertEquals(List.of("README.md"), report.skipped());
    }

    @Test
    void templateFilesAreOverwrittenAndRuntimeFilesStay() throws Exception {
        write(template().resolve("server.properties"), "server-port={{VECTORA_SERVER_PORT}}\n");
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
        write(template().resolve("a.yml"), "a: {{VECTORA_SERVER_PORT}}\n");
        write(template().resolve("plugins/x/config.yml"), "a: 1\nb: {{VECTORA_UNKNOWN}}\n");

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
                "a.cfg", "a.ini", "a.sql", "a.md", "start.bat", "start.cmd", "x.Properties")) {
            assertTrue(TemplateRenderer.isRenderedFile(name), name);
        }
        for (String name : List.of("paper.jar", "icon.png", "level.dat", "key.pem", "forwarding.secret", "yml", ".yml.bak",
                "start.sh", "noext")) {
            assertFalse(TemplateRenderer.isRenderedFile(name), name);
        }
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
