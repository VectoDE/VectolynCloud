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
        assertEquals(TemplateFiles.Outcome.CREATED, files.write("Proxy", "default/plugins/new/config.yml", first, null));
        assertArrayEquals(first, files.file("Proxy", "default/plugins/new/config.yml"));

        TemplateFiles.PreconditionException missing = assertThrows(TemplateFiles.PreconditionException.class,
                () -> files.write("Proxy", "default/plugins/new/config.yml", first, null));
        assertTrue(missing.required(), "replacing needs If-Match");
        TemplateFiles.PreconditionException wrong = assertThrows(TemplateFiles.PreconditionException.class,
                () -> files.write("Proxy", "default/plugins/new/config.yml", first, "\"0123\""));
        assertFalse(wrong.required());
        assertThrows(TemplateFiles.PreconditionException.class,
                () -> files.write("Proxy", "default/other.yml", first, "*"), "* needs an existing file");
        assertFalse(Files.exists(templates.resolve("Proxy/default/other.yml")));
        assertThrows(TemplateFiles.PreconditionException.class,
                () -> files.write("Proxy", "default/a/b/c.yml", first, "*"));
        assertFalse(Files.exists(templates.resolve("Proxy/default/a")), "a refused write creates no folder");

        byte[] second = "a: 2\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(TemplateFiles.Outcome.REPLACED,
                files.write("Proxy", "default/plugins/new/config.yml", second, TemplateFiles.etag(first)));
        assertArrayEquals(second, files.file("Proxy", "default/plugins/new/config.yml"));
        assertThrows(TemplateFiles.PreconditionException.class, () -> files.write("Proxy",
                "default/plugins/new/config.yml", first, TemplateFiles.etag(first)), "a stale ETag loses");
        assertEquals(TemplateFiles.Outcome.REPLACED, files.write("Proxy", "default/plugins/new/config.yml", second, "*"));

        assertThrows(IllegalArgumentException.class, () -> files.write("Proxy", "default/key.pem", first, null));
        assertThrows(IllegalArgumentException.class, () -> files.write("Proxy", "../escape.yml", first, null));
        assertThrows(NoSuchFileException.class, () -> files.write("Missing", "default/a.yml", first, null));
        assertThrows(FileSystemException.class,
                () -> files.write("Proxy", "default/velocity.toml/inside.yml", first, null), "a file is in the way");
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
