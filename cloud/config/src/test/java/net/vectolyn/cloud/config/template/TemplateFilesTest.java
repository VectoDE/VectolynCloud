// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config.template;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import net.vectolyn.cloud.config.CloudHome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateFilesTest {

    @TempDir
    Path home;

    private Path templates;
    private TemplateFiles files;

    @BeforeEach
    void setUp() throws IOException {
        templates = CloudHome.of(home).templatesDir();
        write("Lobby-Server/default/server.properties", "motd=Lobby\n");
        write("Lobby-Server/default/plugins/LuckPerms/config.yml", "storage-method: h2\n");
        write("Lobby-Server/default/forwarding.secret", "s3cret");
        write("Lobby-Server/default/secrets/token.txt", "x");
        write("Lobby-Server/default/.git/config", "x");
        write("Proxy/default/velocity.toml", "bind = \"0.0.0.0:25565\"\n");
        Files.createDirectories(templates.resolve("bad name!"));
        Files.writeString(templates.resolve("not-a-folder"), "x");
        files = new TemplateFiles(CloudHome.of(home));
    }

    private void write(String path, String content) throws IOException {
        Path file = templates.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    void listsTemplatesAndOnlyTheFilesTheCloudHandsOut() throws Exception {
        assertEquals(List.of("Lobby-Server", "Proxy"), files.templates());
        TemplateFiles.Listing listing = files.files("Lobby-Server");
        assertEquals(List.of("default/plugins/LuckPerms/config.yml", "default/server.properties"),
                listing.files().stream().map(TemplateFiles.FileInfo::path).toList());
        assertEquals(11, listing.files().get(1).size());
        assertFalse(listing.truncated());
        assertThrows(NoSuchFileException.class, () -> files.files("Missing"));
        assertThrows(IllegalArgumentException.class, () -> files.files("../Proxy"));
        assertEquals(List.of(), new TemplateFiles(CloudHome.of(home.resolve("empty"))).templates());
    }

    @Test
    void readsAFileButNeverASecretOrAPathOutside() throws Exception {
        assertArrayEquals("motd=Lobby\n".getBytes(StandardCharsets.UTF_8),
                files.file("Lobby-Server", "default/server.properties"));
        assertThrows(NoSuchFileException.class, () -> files.file("Lobby-Server", "default/missing.yml"));
        assertThrows(NoSuchFileException.class, () -> files.file("Lobby-Server", "nothing/here/missing.yml"));
        for (String path : new String[] {"default/forwarding.secret", "default/secrets/token.txt", "default/.git/config",
                "../Proxy/default/velocity.toml", "default/../../Proxy/default/velocity.toml", "/etc/passwd",
                "default\\server.properties", "C:/Windows/win.ini", "default/./server.properties", "", "default/",
                "default/NUL", "default/con.txt"}) {
            assertThrows(IllegalArgumentException.class, () -> files.file("Lobby-Server", path), path);
        }
        assertThrows(FileSystemException.class, () -> files.file("Lobby-Server", "default"), "a folder is no file");
    }

    @Test
    void tooLargeFilesAreRefused() throws Exception {
        Files.write(templates.resolve("Proxy/default/big.bin"), new byte[TemplateFiles.MAX_READ_BYTES + 1]);
        assertThrows(TemplateFiles.TooLargeException.class, () -> files.file("Proxy", "default/big.bin"));
        assertThrows(TemplateFiles.TooLargeException.class,
                () -> files.write("Proxy", "default/new.bin", new byte[TemplateFiles.MAX_WRITE_BYTES + 1], null));
        assertFalse(Files.exists(templates.resolve("Proxy/default/new.bin")));
    }

    @Test
    void writesWithETagChecks() throws Exception {
        byte[] first = "a: 1\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(TemplateFiles.Outcome.CREATED, files.write("Proxy", "default/config/new/config.yml", first, null));
        assertArrayEquals(first, files.file("Proxy", "default/config/new/config.yml"));

        TemplateFiles.PreconditionException missing = assertThrows(TemplateFiles.PreconditionException.class,
                () -> files.write("Proxy", "default/config/new/config.yml", first, null));
        assertTrue(missing.required(), "replacing needs If-Match");
        TemplateFiles.PreconditionException wrong = assertThrows(TemplateFiles.PreconditionException.class,
                () -> files.write("Proxy", "default/config/new/config.yml", first, "\"0123\""));
        assertFalse(wrong.required());
        assertThrows(TemplateFiles.PreconditionException.class,
                () -> files.write("Proxy", "default/other.yml", first, "*"), "* needs an existing file");
        assertFalse(Files.exists(templates.resolve("Proxy/default/other.yml")));
        assertThrows(TemplateFiles.PreconditionException.class,
                () -> files.write("Proxy", "default/a/b/c.yml", first, "*"));
        assertFalse(Files.exists(templates.resolve("Proxy/default/a")), "a refused write creates no folder");

        byte[] second = "a: 2\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(TemplateFiles.Outcome.REPLACED,
                files.write("Proxy", "default/config/new/config.yml", second, TemplateFiles.etag(first)));
        assertArrayEquals(second, files.file("Proxy", "default/config/new/config.yml"));
        assertThrows(TemplateFiles.PreconditionException.class, () -> files.write("Proxy",
                "default/config/new/config.yml", first, TemplateFiles.etag(first)), "a stale ETag loses");
        assertEquals(TemplateFiles.Outcome.REPLACED, files.write("Proxy", "default/config/new/config.yml", second, "*"));

        assertThrows(IllegalArgumentException.class, () -> files.write("Proxy", "default/key.pem", first, null));
        assertThrows(IllegalArgumentException.class, () -> files.write("Proxy", "../escape.yml", first, null));
        assertThrows(NoSuchFileException.class, () -> files.write("Missing", "default/a.yml", first, null));
        assertThrows(FileSystemException.class,
                () -> files.write("Proxy", "default/velocity.toml/inside.yml", first, null), "a file is in the way");
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private TemplateFiles.RefusedException refused(String path, byte[] content, String ifMatch) {
        return assertThrows(TemplateFiles.RefusedException.class, () -> files.write("Proxy", path, content, ifMatch),
                path);
    }

    @Test
    void onlyTextConfigurationOutsideCodeFoldersIsWritten() throws Exception {
        for (String path : new String[] {"default/paper.jar", "default/x.JAR", "default/Main.class", "default/a.zip",
                "default/start.sh", "default/start.bat", "default/run.ps1", "default/lib.so", "default/lib.dll",
                "default/plugins/LuckPerms/config.yml", "default/Plugins/x.yml", "default/MODS/x.toml",
                "default/libraries/x.json", "default/versions/x.json", "default/bundler/x.properties",
                "default/cache/x.yml", "default/vectolyn-mariadb.ini", "default/README.md"}) {
            assertEquals(TemplateFiles.RefusedException.Reason.NOT_EDITABLE, refused(path, utf8("a: 1\n"), null).reason());
            assertFalse(Files.exists(templates.resolve("Proxy").resolve(path)), path);
        }
        assertFalse(Files.exists(templates.resolve("Proxy/default/plugins")), "a refused write creates no folder");
        assertEquals(TemplateFiles.Outcome.CREATED, files.write("Proxy", "default/Config.Yml", utf8("a: 1\n"), null));
        assertEquals(TemplateFiles.Outcome.CREATED,
                files.write("Proxy", "default/config/luckperms/luckperms.conf", utf8("a = 1\n"), null));
        // Path rules and secrets still answer as before, not as "not editable".
        for (String path : new String[] {"../x.yml", "default/../../x.yml", "default\\x.yml", "default/key.pem"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> files.write("Proxy", path, utf8("a"), null), path);
            assertFalse(e instanceof TemplateFiles.RefusedException, path);
        }
    }

    @Test
    void binaryContentIsRefused() {
        assertEquals(TemplateFiles.RefusedException.Reason.NOT_TEXT,
                refused("default/a.yml", new byte[] {'a', 0, 'b'}, null).reason());
        assertEquals(TemplateFiles.RefusedException.Reason.NOT_TEXT,
                refused("default/a.yml", new byte[] {'P', 'K', 3, 4, (byte) 0xff, (byte) 0xfe}, null).reason());
        assertFalse(Files.exists(templates.resolve("Proxy/default/a.yml")));
    }

    @Test
    void secretPlaceholdersCannotBeAdded() throws Exception {
        for (String content : new String[] {"secret: {{VECTOLYN_FORWARDING_SECRET}}\n",
                "k: {{VECTOLYN_FLOODGATE_KEY_B64}}\n", "p: {{VECTOLYN_DB_PASSWORD}}\n",
                "r: {{VECTOLYN_DB_ROOT_PASSWORD}}\n", "m: {{VECTOLYN_MANAGEMENT_SECRET}}\n",
                "l: {{VECTOLYN_LEGACY_FORWARDING_SECRET}}\n", "t: {{VECTOLYN_SOME_TOKEN}}\n",
                "e: {{{{VECTOLYN_FORWARDING_SECRET}}\n"}) {
            assertEquals(TemplateFiles.RefusedException.Reason.SECRET_PLACEHOLDER,
                    refused("default/new.yml", utf8(content), null).reason(), content);
        }
        assertFalse(Files.exists(templates.resolve("Proxy/default/new.yml")));
        assertEquals(TemplateFiles.Outcome.CREATED,
                files.write("Proxy", "default/ports.yml", utf8("port: {{VECTOLYN_PROXY_PORT}}\n"), null),
                "ordinary placeholders are fine");

        byte[] original = utf8("a: 1\nsecret: \"{{VECTOLYN_FORWARDING_SECRET}}\"\n");
        Path file = templates.resolve("Proxy/default/config/paper-global.yml");
        Files.createDirectories(file.getParent());
        Files.write(file, original);
        String etag = TemplateFiles.etag(original);

        byte[] edited = utf8("a: 2\nsecret: \"{{VECTOLYN_FORWARDING_SECRET}}\"\n");
        assertEquals(TemplateFiles.Outcome.REPLACED, files.write("Proxy", "default/config/paper-global.yml",
                edited, etag), "an edit that keeps the placeholder is fine");
        String next = TemplateFiles.etag(edited);

        for (String content : new String[] {"a: 2\nsecret: \"{{VECTOLYN_FORWARDING_SECRET}}\"\nmotd: "
                + "\"{{VECTOLYN_FORWARDING_SECRET}}\"\n", "a: 2\nsecret: \"{{VECTOLYN_FORWARDING_SECRET}}\"\n"
                + "db: {{VECTOLYN_DB_PASSWORD}}\n"}) {
            assertEquals(TemplateFiles.RefusedException.Reason.SECRET_PLACEHOLDER,
                    refused("default/config/paper-global.yml", utf8(content), next).reason(), content);
        }
        assertArrayEquals(edited, Files.readAllBytes(file), "a refused write leaves the file as it was");

        byte[] dropped = utf8("a: 3\n");
        assertEquals(TemplateFiles.Outcome.REPLACED,
                files.write("Proxy", "default/config/paper-global.yml", dropped, next), "dropping it is fine");
        assertEquals(TemplateFiles.RefusedException.Reason.SECRET_PLACEHOLDER,
                refused("default/config/paper-global.yml", edited, TemplateFiles.etag(dropped)).reason(),
                "once dropped it cannot come back through the API");
    }

    @Test
    void secretPlaceholdersAreCounted() {
        assertEquals(java.util.Map.of("VECTOLYN_DB_PASSWORD", 2, "VECTOLYN_FLOODGATE_KEY_B64", 1),
                TemplateFiles.secretPlaceholders("{{VECTOLYN_DB_PASSWORD}} {{VECTOLYN_DB_PASSWORD}}"
                        + " {{VECTOLYN_FLOODGATE_KEY_B64}} {{VECTOLYN_PROXY_PORT}} {{vectolyn_db_password}}"));
    }

    @Test
    void etagHasTheFormOfTheConfigETags() {
        String etag = TemplateFiles.etag("x".getBytes(StandardCharsets.UTF_8));
        assertTrue(etag.matches("\"[0-9a-f]{32}\""), etag);
    }

    @Test
    void linksAreNeverFollowed() throws Exception {
        Path outside = Files.createDirectories(home.resolve("outside"));
        Files.writeString(outside.resolve("passwd"), "root:x");
        try {
            Files.createSymbolicLink(templates.resolve("Proxy/default/link"), outside);
            Files.createSymbolicLink(templates.resolve("Proxy/default/file-link.txt"), outside.resolve("passwd"));
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links are not available here: " + e.getMessage());
        }
        assertThrows(FileSystemException.class, () -> files.file("Proxy", "default/link/passwd"));
        assertThrows(FileSystemException.class, () -> files.file("Proxy", "default/file-link.txt"));
        assertThrows(FileSystemException.class, () -> files.write("Proxy", "default/link/new.txt",
                new byte[] {1}, null));
        assertFalse(Files.exists(outside.resolve("new.txt")));
        assertTrue(files.files("Proxy").files().stream().noneMatch(f -> f.path().contains("link")));
    }
}
