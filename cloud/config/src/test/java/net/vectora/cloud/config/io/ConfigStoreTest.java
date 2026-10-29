package net.vectora.cloud.config.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.Samples;
import net.vectora.cloud.config.error.ConfigError;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.error.ErrorCode;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStoreTest {

    @TempDir
    Path root;

    @Test
    void emptyHomeLoadsDefaultsAndReportsMissingFiles() throws ConfigException {
        ConfigStore.Loaded loaded = new ConfigStore(CloudHome.of(root)).load();
        assertEquals(ConfigSnapshot.defaults(), loaded.snapshot());
        assertEquals(Set.of("cloud.yml", "nodes.yml"), loaded.missingFiles());
        assertTrue(loaded.migrated().isEmpty());
    }

    @Test
    void savedSnapshotLoadsBackEqual() throws IOException, ConfigException {
        ConfigStore store = new ConfigStore(CloudHome.of(root));
        ConfigSnapshot snapshot = Samples.snapshot();
        Set<String> written = store.save(null, snapshot, Set.of());
        assertEquals(Set.of("cloud.yml", "nodes.yml", "groups/lobby.yml", "tasks/lobby.yml"), written);
        assertTrue(Files.isRegularFile(root.resolve("groups/lobby.yml")));
        assertEquals(snapshot, store.load().snapshot());
    }

    @Test
    void savesOnlyChangedFilesAndDeletesRemovedOnes() throws IOException {
        ConfigStore store = new ConfigStore(CloudHome.of(root));
        ConfigSnapshot before = Samples.snapshot();
        store.save(null, before, Set.of());
        ConfigSnapshot after = before.withCloud(before.cloud().withLanguage(Language.DE)).withoutTask("lobby");
        assertEquals(Set.of("cloud.yml", "tasks/lobby.yml"), store.save(before, after, Set.of()));
        assertFalse(Files.exists(root.resolve("tasks/lobby.yml")));
        assertEquals(Set.of("nodes.yml"), store.save(after, after, Set.of("nodes.yml")));
    }

    @Test
    void reportsErrorsOfAllFilesTogether() throws IOException {
        Files.createDirectories(root.resolve("groups"));
        Files.createDirectories(root.resolve("tasks"));
        Files.writeString(root.resolve("cloud.yml"), "schema-version: 1\nnetwrk-name: x\n");
        Files.writeString(root.resolve("groups/lobby.yml"), "schema-version: 7\n");
        Files.writeString(root.resolve("groups/Bad_Name.yml"), "schema-version: 1\n");
        Files.writeString(root.resolve("tasks/lobby.yaml"), "schema-version: 1\n");
        Files.writeString(root.resolve("tasks/notes.txt"), "ignored");
        Files.writeString(root.resolve("tasks/.lobby.yml.swp"), "ignored");

        ConfigException e = assertThrows(ConfigException.class, () -> new ConfigStore(CloudHome.of(root)).load());
        Set<String> found = e.errors().stream().map(err -> err.file() + " " + err.code()).collect(Collectors.toSet());
        assertEquals(Set.of(
                "cloud.yml UNKNOWN_FIELD",
                "groups/lobby.yml SCHEMA_VERSION_UNSUPPORTED",
                "groups/Bad_Name.yml INVALID_FILE_NAME",
                "tasks/lobby.yaml INVALID_FILE_NAME"), found);
    }

    @Test
    void writtenFilesAreReadableYamlWithHeader() throws IOException {
        new ConfigStore(CloudHome.of(root)).save(null, Samples.snapshot(), Set.of());
        String group = Files.readString(root.resolve("groups/lobby.yml"), StandardCharsets.UTF_8);
        assertTrue(group.startsWith("# Written by VectoraCloud"), group);
        assertTrue(group.contains("- id: luckperms"), group);
    }

    @Test
    void homeResolvesOnlyConfigurationFiles() {
        CloudHome home = CloudHome.of(root);
        assertEquals(root.resolve("groups").resolve("lobby.yml").toAbsolutePath().normalize(), home.resolve("groups/lobby.yml"));
        assertEquals(home.cloudFile(), home.resolve("cloud.yml"));
        for (String bad : new String[] {"../cloud.yml", "groups/../../x.yml", "secrets/vectora.env", "groups/A.yml",
                "groups/lobby.txt", "groups/sub/lobby.yml", ""}) {
            assertThrows(IllegalArgumentException.class, () -> home.resolve(bad), bad);
        }
        for (String name : new String[] {"..", ".hidden", "a.b", "a\\b", "a/b", "con", "com1", "lpt9", "a b",
                "a".repeat(33)}) {
            for (String folder : new String[] {CloudHome.GROUPS_DIR, CloudHome.TASKS_DIR}) {
                String bad = folder + "/" + name + CloudHome.EXTENSION;
                assertThrows(IllegalArgumentException.class, () -> home.resolve(bad), bad);
            }
        }
        assertEquals(root.resolve("tasks").resolve("console.yml").toAbsolutePath().normalize(),
                home.resolve("tasks/console.yml"));
    }

    @Test
    void unreadableFolderEntriesDoNotHideOtherErrors() throws IOException {
        Files.createDirectories(root.resolve("groups/sub.yml"));
        ConfigStore.Loaded loaded = assertLoads(new ConfigStore(CloudHome.of(root)));
        assertTrue(loaded.snapshot().groups().isEmpty(), "folders inside groups/ are skipped");
    }

    private static ConfigStore.Loaded assertLoads(ConfigStore store) {
        try {
            return store.load();
        } catch (ConfigException e) {
            throw new AssertionError(e.errors().stream().map(ConfigError::toString).toList().toString(), e);
        }
    }
}
