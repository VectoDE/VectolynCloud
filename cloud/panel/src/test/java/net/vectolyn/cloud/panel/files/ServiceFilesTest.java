// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceFilesTest {

    @TempDir
    Path home;

    @Test
    void pathsAreRelativeAndPlain() throws Exception {
        assertEquals(List.of(), ServiceFiles.parsePath(""));
        assertEquals(List.of("plugins", "LuckPerms", "config.yml"), ServiceFiles.parsePath("plugins/LuckPerms/config.yml"));
        assertEquals(List.of("..x", "a b"), ServiceFiles.parsePath("..x/a b"), "dots inside a name are fine");
        for (String bad : List.of("/etc/passwd", "a/", "a//b", ".", "..", "a/../b", "a/./b", "a\\b", "C:/x", "c:",
                "a\u0000b", "a\nb", "con", "NUL.txt", "lpt1", "name.", "name ", "a*b", "a?b", "a\"b", "a<b", "a>b",
                "a|b", "x".repeat(256), ("a/").repeat(300) + "a")) {
            ServiceFiles.FileException e = assertThrows(ServiceFiles.FileException.class, () -> ServiceFiles.parsePath(bad), bad);
            assertEquals(400, e.status());
            assertEquals("invalid-path", e.code());
        }
    }

    @Test
    void writeReadListAndDelete() throws Exception {
        Path root = Files.createDirectories(home.resolve("temp/services/lobby-1"));
        ServiceFiles files = new ServiceFiles(home);
        files.write("lobby-1", "plugins/a/config.yml", "x: 1".getBytes(StandardCharsets.UTF_8));
        files.write("lobby-1", "plugins/a/config.yml", "x: 2".getBytes(StandardCharsets.UTF_8));
        files.write("lobby-1", "Zeta.txt", new byte[0]);
        files.write("lobby-1", "alpha.txt", new byte[] {1, 2, 3});
        assertArrayEquals("x: 2".getBytes(StandardCharsets.UTF_8), files.read("lobby-1", "plugins/a/config.yml"));

        List<ServiceFiles.Entry> top = files.list("lobby-1", "");
        assertEquals(List.of("plugins", "alpha.txt", "Zeta.txt"), top.stream().map(ServiceFiles.Entry::name).toList(),
                "folders first, then by name ignoring case");
        assertTrue(top.get(0).folder());
        assertEquals(3, top.get(1).size());
        try (var stream = Files.list(root.resolve("plugins/a"))) {
            assertEquals(1, stream.count(), "no temporary file is left behind");
        }

        ServiceFiles.FileException notEmpty = assertThrows(ServiceFiles.FileException.class,
                () -> files.delete("lobby-1", "plugins"));
        assertEquals(409, notEmpty.status());
        files.delete("lobby-1", "plugins/a/config.yml");
        files.delete("lobby-1", "plugins/a");
        assertFalse(Files.exists(root.resolve("plugins/a")));
        assertEquals(404, assertThrows(ServiceFiles.FileException.class, () -> files.delete("lobby-1", "gone")).status());
        assertEquals(404, assertThrows(ServiceFiles.FileException.class, () -> files.read("lobby-1", "gone")).status());
        assertEquals(409, assertThrows(ServiceFiles.FileException.class,
                () -> files.write("lobby-1", "plugins", new byte[0])).status(), "a folder is not overwritten");
        assertEquals(409, assertThrows(ServiceFiles.FileException.class,
                () -> files.createFolder("lobby-1", "alpha.txt/sub")).status(), "a file is not a folder");
    }

    @Test
    void servicesAreLookedUpInTempThenLocal() throws Exception {
        Files.createDirectories(home.resolve("local/services/proxy-1"));
        ServiceFiles files = new ServiceFiles(home);
        assertEquals(home.resolve("local/services/proxy-1"), files.serviceRoot("proxy-1"));
        Files.createDirectories(home.resolve("temp/services/proxy-1"));
        assertEquals(home.resolve("temp/services/proxy-1"), files.serviceRoot("proxy-1"));
        for (String bad : List.of("", "..", "../secrets", "Lobby", "a/b", "-x", "x".repeat(65))) {
            assertEquals(400, assertThrows(ServiceFiles.FileException.class, () -> files.serviceRoot(bad)).status(), bad);
        }
        assertEquals(404, assertThrows(ServiceFiles.FileException.class, () -> files.serviceRoot("nope")).status());
    }

    @Test
    void largeFilesAreNotRead() throws Exception {
        Path root = Files.createDirectories(home.resolve("temp/services/lobby-1"));
        try (RandomAccessFile file = new RandomAccessFile(root.resolve("world.dat").toFile(), "rw")) {
            file.setLength(ServiceFiles.MAX_READ_BYTES + 1);
        }
        ServiceFiles.FileException e = assertThrows(ServiceFiles.FileException.class,
                () -> new ServiceFiles(home).read("lobby-1", "world.dat"));
        assertEquals(413, e.status());
    }

    @Test
    void linksAreNeverFollowed() throws Exception {
        Path root = Files.createDirectories(home.resolve("temp/services/lobby-1"));
        Path outside = Files.createDirectories(home.resolve("secrets"));
        Files.writeString(outside.resolve("vectolyn.env"), "SECRET=1");
        try {
            Files.createSymbolicLink(root.resolve("escape"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "this system does not let the test create links");
        }
        ServiceFiles files = new ServiceFiles(home);
        assertEquals(403, assertThrows(ServiceFiles.FileException.class,
                () -> files.read("lobby-1", "escape/vectolyn.env")).status());
        assertEquals(403, assertThrows(ServiceFiles.FileException.class,
                () -> files.write("lobby-1", "escape/new.txt", new byte[0])).status());
        assertEquals(403, assertThrows(ServiceFiles.FileException.class, () -> files.list("lobby-1", "escape")).status());
        assertTrue(files.list("lobby-1", "").isEmpty(), "links are left out of listings");
        assertFalse(Files.exists(outside.resolve("new.txt")));
    }
}
