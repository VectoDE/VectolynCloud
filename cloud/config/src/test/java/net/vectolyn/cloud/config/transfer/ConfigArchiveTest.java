// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config.transfer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.ConfigPreview;
import net.vectolyn.cloud.config.ConfigService;
import net.vectolyn.cloud.config.Samples;
import net.vectolyn.cloud.config.error.ConfigError;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.error.ErrorCode;
import net.vectolyn.cloud.config.io.ConfigMappers;
import net.vectolyn.cloud.config.model.Language;
import net.vectolyn.cloud.config.operation.PutGroup;
import net.vectolyn.cloud.config.operation.PutTask;
import net.vectolyn.cloud.config.operation.SetLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigArchiveTest {

    /** monitor.yml stand-in: any content but "bad" is accepted. */
    static final ConfigArchive.ExtraFile MONITOR = new ConfigArchive.ExtraFile("monitor.yml", content -> {
        if (new String(content, StandardCharsets.UTF_8).contains("bad")) {
            throw new ConfigException(ConfigError.of("monitor.yml", "x", ErrorCode.INVALID_VALUE, "good"));
        }
    });

    @TempDir
    Path temp;

    private ConfigService open(String name) throws Exception {
        return ConfigService.open(CloudHome.of(temp.resolve(name)));
    }

    @Test
    void roundTripCarriesEveryFileAndNoSecret() throws Exception {
        Path archive = temp.resolve("export.zip");
        try (ConfigService source = open("a")) {
            source.apply(List.of(new SetLanguage(Language.DE), new PutGroup("lobby", Samples.lobbyGroup()),
                    new PutTask("lobby", Samples.lobbyTask())));
            Files.writeString(source.home().monitorFile(), "schema-version: 1\n");
            Files.createDirectories(source.home().secretsDir());
            Files.writeString(source.home().secretsFile(), "VECTOLYN_DB_PASSWORD=test-secret-0000\n");
            Files.writeString(source.home().configDir().resolve("cluster.yml"), "head: x\n");

            List<String> entries = new ConfigArchive(source, List.of(MONITOR)).export(archive);
            assertEquals(List.of("cloud.yml", "groups/lobby.yml", "nodes.yml", "tasks/lobby.yml", "monitor.yml"), entries);
            String raw = new String(Files.readAllBytes(archive), StandardCharsets.ISO_8859_1);
            assertFalse(raw.contains("test-secret-0000"));
            assertFalse(raw.contains("cluster.yml"));
            assertThrows(FileAlreadyExistsException.class, () -> new ConfigArchive(source, List.of(MONITOR)).export(archive));
        }
        try (ConfigService target = open("b")) {
            ConfigArchive tool = new ConfigArchive(target, List.of(MONITOR));
            ConfigArchive.Plan plan = tool.read(archive);
            assertEquals(List.of("cloud.yml", "groups/lobby.yml", "monitor.yml", "tasks/lobby.yml"),
                    plan.changes().stream().map(ConfigPreview.FileChange::file).toList());
            assertTrue(plan.diff().contains("+language: de"), plan.diff());
            assertTrue(Files.notExists(target.home().groupsDir().resolve("lobby.yml")), "read writes nothing");

            tool.apply(plan);
            assertEquals(Language.DE, target.snapshot().cloud().language());
            assertEquals(Samples.lobbyGroup(), target.snapshot().groups().get("lobby"));
            assertEquals("schema-version: 1\n", Files.readString(target.home().monitorFile()));
            assertTrue(tool.read(archive).isEmpty(), "a second import changes nothing");
        }
    }

    @Test
    void importDeletesGroupsAndTasksTheArchiveDoesNotHave() throws Exception {
        Path archive = temp.resolve("empty.zip");
        try (ConfigService source = open("a")) {
            new ConfigArchive(source, List.of()).export(archive);
        }
        try (ConfigService target = open("b")) {
            target.apply(List.of(new PutGroup("lobby", Samples.lobbyGroup()), new PutTask("lobby", Samples.lobbyTask())));
            ConfigArchive tool = new ConfigArchive(target, List.of());
            ConfigArchive.Plan plan = tool.read(archive);
            assertTrue(plan.changes().stream().allMatch(c -> c.kind() == ConfigPreview.Kind.REMOVED), plan.diff());
            tool.apply(plan);
            assertTrue(target.snapshot().groups().isEmpty());
            assertTrue(Files.notExists(target.home().tasksDir().resolve("lobby.yml")));
        }
    }

    @Test
    void entriesOutsideTheWhitelistAreRefused() throws Exception {
        try (ConfigService target = open("b")) {
            ConfigArchive tool = new ConfigArchive(target, List.of(MONITOR));
            Map<String, byte[]> base = new ConfigArchive(target, List.of()).exportEntries();
            for (String evil : List.of("../cloud.yml", "/etc/passwd", "groups/../../x.yml", "groups/a/b.yml",
                    "..\\secrets\\vectolyn.env", "secrets/vectolyn.env", "cluster.yml", "software.lock.yml",
                    "groups/Lobby.yml", "groups/lobby.yaml", "C:/x.yml", "groups/con.yml", "backup.yml")) {
                Map<String, byte[]> entries = new LinkedHashMap<>(base);
                entries.put(evil, "x: 1\n".getBytes(StandardCharsets.UTF_8));
                ConfigException e = assertThrows(ConfigException.class, () -> tool.read(zip(entries)), evil);
                assertEquals(ErrorCode.NOT_IN_ARCHIVE, e.errors().getFirst().code(), evil);
            }
            assertTrue(Files.notExists(temp.resolve("x.yml")));
        }
    }

    @Test
    void brokenArchivesAreRefusedWithoutWriting() throws Exception {
        try (ConfigService target = open("b")) {
            byte[] cloudBefore = Files.readAllBytes(target.home().cloudFile());
            ConfigArchive tool = new ConfigArchive(target, List.of(MONITOR));
            Map<String, byte[]> base = new ConfigArchive(target, List.of()).exportEntries();

            Map<String, byte[]> noCloud = new LinkedHashMap<>(base);
            noCloud.remove("cloud.yml");
            assertEquals(ErrorCode.FILE_MISSING, refused(tool, zip(noCloud)).code());

            Map<String, byte[]> badYaml = new LinkedHashMap<>(base);
            badYaml.put("groups/lobby.yml", "schema-version: 1\ntemplat: x\n".getBytes(StandardCharsets.UTF_8));
            ConfigError error = refused(tool, zip(badYaml));
            assertEquals(ErrorCode.UNKNOWN_FIELD, error.code());
            assertEquals("groups/lobby.yml", error.file());

            Map<String, byte[]> badExtra = new LinkedHashMap<>(base);
            badExtra.put("monitor.yml", "bad".getBytes(StandardCharsets.UTF_8));
            assertEquals("monitor.yml", refused(tool, zip(badExtra)).file());

            Map<String, byte[]> invalid = new LinkedHashMap<>(base);
            invalid.put("tasks/orphan.yml", """
                    schema-version: 2
                    group: missing
                    scaling:
                      static-services: 0
                      min-services: 0
                      max-services: 1
                      players-per-service: 10
                      scale-up-at: 75
                      scale-down-after: 5m
                      cooldown: 60s
                    """.getBytes(StandardCharsets.UTF_8));
            assertEquals(ErrorCode.UNKNOWN_GROUP, refused(tool, zip(invalid)).code());

            // ZipInputStream sees no entry in bytes that are no zip.
            assertEquals(ErrorCode.EMPTY, refused(tool, "not a zip".getBytes(StandardCharsets.UTF_8)).code());
            assertEquals(ErrorCode.EMPTY, refused(tool, zip(Map.of())).code());

            Map<String, byte[]> large = new LinkedHashMap<>(base);
            large.put("groups/big.yml", new byte[ConfigMappers.MAX_FILE_BYTES + 1]);
            assertEquals(ErrorCode.FILE_TOO_LARGE, refused(tool, zip(large)).code());

            assertArrayEquals(cloudBefore, Files.readAllBytes(target.home().cloudFile()));
            assertThrows(ConfigException.class, () -> tool.read(temp.resolve("missing.zip")));
        }
    }

    @Test
    void duplicateAndTooManyEntriesAreRefused() throws Exception {
        try (ConfigService target = open("b")) {
            ConfigArchive tool = new ConfigArchive(target, List.of());
            Map<String, byte[]> many = new LinkedHashMap<>(tool.exportEntries());
            for (int i = 0; i < ConfigArchive.MAX_ENTRIES; i++) {
                many.put("groups/g" + i + ".yml", new byte[0]);
            }
            assertEquals(ErrorCode.ARCHIVE_TOO_LARGE, refused(tool, zip(many)).code());
            assertEquals(ErrorCode.DUPLICATE_ENTRY, refused(tool, duplicated(tool.exportEntries())).code());
        }
    }

    @Test
    void aClusterMemberRefusesTheHeadsFiles() throws Exception {
        Path archive = temp.resolve("with-group.zip");
        try (ConfigService source = open("a")) {
            source.apply(new PutGroup("lobby", Samples.lobbyGroup()));
            new ConfigArchive(source, List.of()).export(archive);
        }
        try (ConfigService member = open("b")) {
            member.mirrorMode(true);
            ConfigException e = assertThrows(ConfigException.class, () -> new ConfigArchive(member, List.of()).read(archive));
            assertEquals(ErrorCode.MANAGED_BY_HEAD, e.errors().getFirst().code());
            assertTrue(member.snapshot().groups().isEmpty());
        }
    }

    @Test
    void exportIsReproducible() throws Exception {
        try (ConfigService source = open("a")) {
            ConfigArchive tool = new ConfigArchive(source, List.of());
            tool.export(temp.resolve("1.zip"));
            tool.export(temp.resolve("2.zip"));
            assertArrayEquals(Files.readAllBytes(temp.resolve("1.zip")), Files.readAllBytes(temp.resolve("2.zip")));
        }
    }

    @Test
    void extraFileNamesAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new ConfigArchive.ExtraFile("../x.yml", c -> { }));
        assertThrows(IllegalArgumentException.class, () -> new ConfigArchive.ExtraFile("cloud.yml", c -> { }));
        assertThrows(IllegalArgumentException.class, () -> new ConfigArchive.ExtraFile("secrets/a.yml", c -> { }));
    }

    private static ConfigError refused(ConfigArchive tool, byte[] archive) {
        ConfigException e = assertThrows(ConfigException.class, () -> tool.read(archive));
        return e.errors().getFirst();
    }

    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
            }
        }
        return bytes.toByteArray();
    }

    /** An archive with cloud.yml twice: written under a placeholder name and patched afterwards. */
    private static byte[] duplicated(Map<String, byte[]> entries) throws IOException {
        Map<String, byte[]> withPlaceholder = new LinkedHashMap<>(entries);
        withPlaceholder.put("cloud.ym_", entries.get("cloud.yml"));
        byte[] bytes = zip(withPlaceholder);
        byte[] from = "cloud.ym_".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i + from.length <= bytes.length; i++) {
            boolean match = true;
            for (int k = 0; k < from.length && match; k++) {
                match = bytes[i + k] == from[k];
            }
            if (match) {
                bytes[i + from.length - 1] = 'l';
            }
        }
        return bytes;
    }
}
