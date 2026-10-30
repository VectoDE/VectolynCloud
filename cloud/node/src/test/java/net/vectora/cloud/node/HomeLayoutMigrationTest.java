package net.vectora.cloud.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.io.ConfigStore;
import net.vectora.cloud.config.model.CloudConfig;
import net.vectora.cloud.config.model.CombatSystem;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.HeapConfig;
import net.vectora.cloud.config.model.JavaSelection;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.model.NodesConfig;
import net.vectora.cloud.config.model.ScalingConfig;
import net.vectora.cloud.config.model.ServerSoftware;
import net.vectora.cloud.config.model.TaskConfig;
import net.vectora.cloud.config.model.TaskMode;
import net.vectora.cloud.config.model.TimeSpan;
import net.vectora.cloud.node.service.ServiceState;
import net.vectora.cloud.node.state.ServiceRecord;
import net.vectora.cloud.node.state.ServiceStateStore;
import net.vectora.cloud.node.state.ServiceStates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HomeLayoutMigrationTest {

    @TempDir
    Path home;

    @TempDir
    Path scratch;

    private BuiltInTemplates builtIn;

    @BeforeEach
    void builtInTemplates() throws Exception {
        Map<String, byte[]> resources = new LinkedHashMap<>();
        StringBuilder index = new StringBuilder();
        for (Map.Entry<String, String> file : Map.of(
                "Lobby-Server/template/server.properties", "motd=built-in",
                "Survival-Server/template/server.properties", "motd=survival",
                "Database/template/init.sql", "CREATE DATABASE x;").entrySet()) {
            byte[] bytes = file.getValue().getBytes(StandardCharsets.UTF_8);
            resources.put(file.getKey(), bytes);
            index.append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .append("  ").append(file.getKey()).append('\n');
        }
        resources.put(BuiltInTemplates.INDEX, index.toString().getBytes(StandardCharsets.UTF_8));
        builtIn = new BuiltInTemplates(name -> {
            byte[] bytes = resources.get(name);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        });
    }

    private HomeLayoutMigration migration(HomeLayoutMigration.Liveness alive) {
        return new HomeLayoutMigration(home, builtIn, alive, now -> "20261030-120000");
    }

    private HomeLayoutMigration migration() {
        return migration((pid, startedAt) -> false);
    }

    /** A home of the first layout: what VectoraCloud up to this change wrote. */
    private void oldHome() throws IOException {
        // The configuration files, written by the real store into a scratch home and put at the top.
        GroupConfig lobby = group("Lobby-Server", null);
        GroupConfig survival = group("Survival-Server", Boolean.FALSE);
        ConfigSnapshot snapshot = ConfigSnapshot.of(CloudConfig.defaults(), NodesConfig.defaults(),
                Map.of("lobby", lobby, "survival", survival), Map.of("lobby", task("lobby")));
        new ConfigStore(CloudHome.of(scratch)).save(null, snapshot, Set.of());
        try (Stream<Path> files = Files.list(scratch.resolve("config"))) {
            for (Path file : files.toList()) {
                Files.move(file, home.resolve(file.getFileName().toString()));
            }
        }
        write("cloud.yml.v1.bak", "old");
        write("monitor.yml", "schema-version: 1\n");
        write("software.lock.yml", "lock");
        write("Lobby-Server/template/server.properties", "motd=mine");
        write("Lobby-Server/runtime/world/level.dat", "world 1");
        write("Lobby-Server/runtime-2/world/level.dat", "world 2");
        write("Survival-Server/template/server.properties", "motd=survival");
        write("Survival-Server/runtime/world/level.dat", "survival world");
        write("Database/template/init.sql", "CREATE DATABASE x;");
        write("Database/runtime/data/ibdata1", "rows");
        write("Database/runtime/vectora-mariadb.ini", "[mysqld]");
        write("Custom/runtime/notes.txt", "mine");
        write("cache/artifacts/0123", "jar");
        write("cache/libs/1.0.0/jackson.jar", "lib");
        write("jre/temurin-25-linux-x64/release", "JAVA_VERSION=25");
        write("secrets/vectora.env", "VECTORA_X=1");
        write("logs/services/lobby-1.log", "log");
        write("temp/services/minigame-1/x", "x");
    }

    @Test
    void anOldHomeIsMovedExactlyAndBackedUpFirst() throws Exception {
        oldHome();

        HomeLayoutMigration.Result result = migration().run();

        assertEquals(HomeLayoutMigration.Outcome.MIGRATED, result.outcome());
        assertEquals("backups/home-layout-migration-20261030-120000", result.backup().orElseThrow());
        // Configuration
        for (String file : List.of("cloud.yml", "nodes.yml", "monitor.yml", "software.lock.yml", "cloud.yml.v1.bak",
                "groups/lobby.yml", "groups/survival.yml", "tasks/lobby.yml")) {
            assertTrue(Files.isRegularFile(home.resolve("config").resolve(file)), file);
            assertFalse(Files.exists(home.resolve(file)), file);
        }
        // Templates: the edited one is kept, the unchanged copies of unwanted servers are dropped.
        assertEquals("motd=mine", read("local/templates/Lobby-Server/default/server.properties"));
        assertFalse(Files.exists(home.resolve("local/templates/Survival-Server")));
        assertFalse(Files.exists(home.resolve("local/templates/Database")));
        // Services and database, with their content.
        assertEquals("world 1", read("local/services/lobby-1/world/level.dat"));
        assertEquals("world 2", read("local/services/lobby-2/world/level.dat"));
        assertEquals("survival world", read("local/services/survival-1/world/level.dat"),
                "a world of a server that is switched off is kept");
        assertEquals("rows", read("database/data/ibdata1"));
        assertEquals("[mysqld]", read("database/vectora-mariadb.ini"));
        // Downloads
        assertEquals("jar", read("storage/artifacts/0123"));
        assertEquals("JAVA_VERSION=25", read("storage/jre/temurin-25-linux-x64/release"));
        assertFalse(Files.exists(home.resolve("storage/libs/1.0.0")), "the launcher's libraries are not moved");
        // By listed name: on Windows and macOS "Database" would also find the new "database".
        List<String> top;
        try (java.util.stream.Stream<Path> children = Files.list(home)) {
            top = children.map(child -> child.getFileName().toString()).toList();
        }
        for (String gone : List.of("Lobby-Server", "Survival-Server", "Database", "cache", "jre", "groups", "tasks",
                HomeLayoutMigration.DATABASE_STAGING)) {
            assertFalse(top.contains(gone), gone + " in " + top);
        }
        assertTrue(top.contains("database"), top.toString());
        // What has no owner stays where it was, reported.
        assertEquals("mine", read("Custom/runtime/notes.txt"));
        assertTrue(result.kept().stream().anyMatch(line -> line.startsWith("Custom/runtime")), result.kept().toString());
        // Untouched folders.
        assertEquals("VECTORA_X=1", read("secrets/vectora.env"));
        assertEquals("log", read("logs/services/lobby-1.log"));
        assertEquals("x", read("temp/services/minigame-1/x"));
        // The backup holds what is edited by hand, no world and no secret.
        Path backup = home.resolve(result.backup().get());
        assertTrue(Files.isRegularFile(backup.resolve("cloud.yml")));
        assertTrue(Files.isRegularFile(backup.resolve("groups/lobby.yml")));
        assertEquals("motd=mine", Files.readString(backup.resolve("Lobby-Server/template/server.properties")));
        assertFalse(Files.exists(backup.resolve("Lobby-Server/runtime")));
        assertFalse(Files.exists(backup.resolve("secrets")));
        // The moved configuration still reads.
        assertEquals(Set.of("lobby", "survival"), new ConfigStore(CloudHome.of(home)).load().snapshot().groups().keySet());

        assertEquals("2", read(HomeLayoutMigration.MARKER).strip());
        assertFalse(Files.exists(home.resolve(HomeLayoutMigration.IN_PROGRESS)));
        assertEquals(HomeLayoutMigration.Outcome.CURRENT, migration().run().outcome(), "a second start does nothing");
    }

    @Test
    void anInterruptedMigrationContinuesWithoutASecondBackup() throws Exception {
        oldHome();
        // The first attempt made its backup and moved part of groups/ before it stopped.
        Path backup = home.resolve("backups/home-layout-migration-20261029-080000");
        Files.createDirectories(backup);
        write(HomeLayoutMigration.IN_PROGRESS, "backups/home-layout-migration-20261029-080000\n");
        Files.createDirectories(home.resolve("config/groups"));
        Files.copy(home.resolve("groups/lobby.yml"), home.resolve("config/groups/lobby.yml"));
        Files.createDirectories(home.resolve("local/services/lobby-1/world"));
        Files.move(home.resolve("Lobby-Server/runtime/world/level.dat"),
                home.resolve("local/services/lobby-1/world/level.dat"));

        HomeLayoutMigration.Result result = migration().run();

        assertEquals("backups/home-layout-migration-20261029-080000", result.backup().orElseThrow());
        try (Stream<Path> backups = Files.list(home.resolve("backups"))) {
            assertEquals(1, backups.count(), "no second backup");
        }
        assertTrue(Files.isRegularFile(home.resolve("config/groups/lobby.yml")));
        assertTrue(Files.isRegularFile(home.resolve("config/groups/survival.yml")));
        assertEquals("world 1", read("local/services/lobby-1/world/level.dat"));
        assertFalse(Files.exists(home.resolve("groups")));
        assertFalse(Files.exists(home.resolve("Lobby-Server")));
        assertFalse(Files.exists(home.resolve(HomeLayoutMigration.IN_PROGRESS)));
    }

    @Test
    void twoDifferentFilesAtOnePlaceStopTheMigrationAndNothingIsOverwritten() throws Exception {
        oldHome();
        write("config/cloud.yml", "schema-version: 1\n# someone else's\n");
        String before = read("cloud.yml");

        IOException error = assertThrows(IOException.class, () -> migration().run());

        assertTrue(error.getMessage().contains("cloud.yml") && error.getMessage().contains("differ"), error.getMessage());
        assertEquals(before, read("cloud.yml"));
        assertEquals("schema-version: 1\n# someone else's\n", read("config/cloud.yml"));
        assertFalse(Files.exists(home.resolve(HomeLayoutMigration.MARKER)));
    }

    @Test
    void aServerOfThePreviousRunThatStillWorksStopsTheMigrationBeforeAnythingMoves() throws Exception {
        oldHome();
        new ServiceStateStore(home).save(new ServiceStates(ServiceStates.SCHEMA_VERSION, "node-1", List.of(
                new ServiceRecord("lobby-1", "lobby", "lobby", 1, TaskMode.STATIC, ServiceState.RUNNING, 4242,
                        1_000L, 30001, "Lobby-Server/runtime"))));

        IOException error = assertThrows(IOException.class, () -> migration((pid, started) -> pid == 4242).run());

        assertTrue(error.getMessage().contains("lobby-1 (pid 4242)"), error.getMessage());
        assertTrue(Files.isRegularFile(home.resolve("cloud.yml")));
        assertTrue(Files.isDirectory(home.resolve("Lobby-Server/runtime")));
        assertFalse(Files.exists(home.resolve("backups")), "not even the backup is made");
    }

    @Test
    void aNewHomeOnlyGetsTheMarker() throws Exception {
        HomeLayoutMigration.Result result = migration().run();

        assertEquals(HomeLayoutMigration.Outcome.NEW, result.outcome());
        try (Stream<Path> files = Files.list(home)) {
            assertEquals(List.of("state"), files.map(f -> f.getFileName().toString()).toList());
        }
        assertEquals(HomeLayoutMigration.Outcome.CURRENT, migration().run().outcome());
    }

    @Test
    void anOldFileNextToAMigratedHomeIsNotTouched() throws Exception {
        migration().run();
        write("cloud.yml", "stray");

        assertEquals(HomeLayoutMigration.Outcome.CURRENT, migration().run().outcome());

        assertEquals("stray", read("cloud.yml"));
        assertFalse(Files.exists(home.resolve("config/cloud.yml")));
    }

    private void write(String relative, String content) throws IOException {
        Path file = home.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private String read(String relative) throws IOException {
        return Files.readString(home.resolve(relative), StandardCharsets.UTF_8);
    }

    private static GroupConfig group(String template, Boolean wanted) {
        return new GroupConfig(GroupConfig.SCHEMA_VERSION, template, ServerSoftware.PAPER, "26.3", JavaSelection.AUTO,
                new HeapConfig(MemorySize.parse("256M"), MemorySize.parse("1G")), List.of(), CombatSystem.MODERN,
                null, wanted);
    }

    private static TaskConfig task(String group) {
        return new TaskConfig(TaskConfig.SCHEMA_VERSION, group, new ScalingConfig(2, 2, 2, 50, 80, 50,
                TimeSpan.parse("5m"), TimeSpan.parse("60s")), List.of());
    }
}
