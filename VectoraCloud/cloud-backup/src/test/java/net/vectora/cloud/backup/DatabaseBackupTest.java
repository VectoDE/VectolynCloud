package net.vectora.cloud.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.vectora.cloud.backup.ServiceControl.State;
import net.vectora.cloud.backup.config.DatabaseBackupConfig;
import net.vectora.cloud.backup.database.DatabaseAccess;
import net.vectora.cloud.backup.database.FakeToolRunner;
import net.vectora.cloud.backup.manifest.BackupManifest;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.secret.EnvFileParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The database part of a backup, with a fake dump tool. */
class DatabaseBackupTest {

    private static final String ROOT_PASSWORD = "RootPassword1234567890abcdefXYZ";

    @TempDir
    Path home;

    private TestHome test;
    private FakeToolRunner runner;
    private Path lobby;

    @BeforeEach
    void setUp() throws Exception {
        test = new TestHome(home);
        lobby = test.addServer("lobby-1", "lobby", "Lobby-Server", true);
        runner = new FakeToolRunner();
        Path bin = Files.createDirectories(home.resolve("Database/runtime/mariadb-11.8/bin"));
        Files.createFile(bin.resolve("mariadb-dump"));
    }

    private BackupService service(DatabaseMode mode) throws Exception {
        DatabaseAccess access = DatabaseAccess.of(DatabaseConfig.defaults().withMode(mode),
                EnvFileParser.parse("VECTORA_DB_ROOT_PASSWORD=" + ROOT_PASSWORD + "\nVECTORA_DB_PASSWORD=app-pass-1234567890\n"),
                home.resolve("Database/runtime/mariadb-11.8"));
        return test.builder().database(() -> Optional.of(access)).tools(runner).build();
    }

    @Test
    void managedDatabaseIsDumpedIntoTheArchive() throws Exception {
        BackupInfo info = service(DatabaseMode.MANAGED).create(BackupRequest.all(BackupReason.MANUAL));

        BackupManifest manifest = info.manifest().orElseThrow();
        assertEquals(new BackupManifest.DatabaseEntry("managed", "dumped", "database/luckperms.sql", "mariadb-dump"),
                manifest.database());
        Map<String, String> entries = TestHome.entries(info.file());
        assertTrue(entries.get("database/luckperms.sql").contains("CREATE DATABASE"));
        for (String argument : runner.calls().getFirst().command()) {
            assertFalse(argument.contains(ROOT_PASSWORD));
        }
        for (String content : entries.values()) {
            assertFalse(content.contains(ROOT_PASSWORD), "no password in the archive");
        }
        try (Stream<Path> work = Files.list(home.resolve("temp/backup"))) {
            assertEquals(List.of(), work.toList(), "dump and option file are cleaned up");
        }
    }

    @Test
    void failedDumpIsAWarningForScheduledAndAnErrorForStrictBackups() throws Exception {
        runner.exitCode(2, "Got error: 1045: Access denied");
        BackupInfo info = service(DatabaseMode.MANAGED).create(BackupRequest.all(BackupReason.SCHEDULED));
        BackupManifest.DatabaseEntry db = info.manifest().orElseThrow().database();
        assertEquals("failed", db.status());
        assertNull(db.file());
        assertTrue(db.detail().contains("Access denied"), db.detail());
        assertTrue(info.warnings().getFirst().startsWith("database dump failed"), info.warnings().toString());

        BackupException e = assertThrows(BackupException.class,
                () -> service(DatabaseMode.MANAGED).createBeforeVersionChange("lobby", "26.3", "26.4"));
        assertEquals(BackupException.Reason.DATABASE, e.reason());
    }

    @Test
    void missingToolIsReported() throws Exception {
        DatabaseAccess external = DatabaseAccess.of(new DatabaseConfig(DatabaseMode.EXTERNAL, "db", 3306,
                "luckperms", "luckperms", null), EnvFileParser.parse("VECTORA_DB_PASSWORD=app-pass-1234567890\n"), null);
        BackupService service = test.builder().database(() -> Optional.of(external)).tools(runner).build();
        BackupInfo info = service.create(BackupRequest.all(BackupReason.SCHEDULED));
        assertTrue(info.manifest().orElseThrow().database().detail().contains("on PATH"));
        assertTrue(runner.calls().isEmpty());
    }

    @Test
    void databaseCanBeSwitchedOff() throws Exception {
        test.config = test.config.withDatabase(new DatabaseBackupConfig(false, null, null));
        BackupInfo info = service(DatabaseMode.MANAGED).create(BackupRequest.all(BackupReason.MANUAL));
        assertNull(info.manifest().orElseThrow().database());
        assertTrue(runner.calls().isEmpty());
    }

    @Test
    void dockerGetsAHintInsteadOfADump() throws Exception {
        BackupInfo info = service(DatabaseMode.DOCKER).create(BackupRequest.all(BackupReason.MANUAL));
        assertEquals("manual", info.manifest().orElseThrow().database().status());
        String hint = info.warnings().getFirst();
        assertTrue(hint.contains("docker compose") && hint.contains("mariadb-dump"), hint);
        assertTrue(hint.contains("MYSQL_PWD=\"$MARIADB_ROOT_PASSWORD\""), "password stays an environment variable");
        assertFalse(hint.contains(ROOT_PASSWORD));
        assertTrue(runner.calls().isEmpty());
    }

    @Test
    void h2IsScriptedForStoppedServicesOnly() throws Exception {
        Path libs = Files.createDirectories(lobby.resolve("plugins/LuckPerms/libs"));
        Files.writeString(libs.resolve("h2-driver-2.1.214.jar"), "jar");
        Files.writeString(lobby.resolve("plugins/LuckPerms/luckperms-h2-v2.mv.db"), "h2 data");
        runner.dump("CREATE USER IF NOT EXISTS \"\" PASSWORD '' ADMIN;\n");

        BackupInfo info = service(DatabaseMode.H2).create(BackupRequest.all(BackupReason.MANUAL));

        Map<String, String> entries = TestHome.entries(info.file());
        assertTrue(entries.containsKey("database/lobby-1-luckperms-h2.sql"), entries.keySet().toString());
        assertEquals("h2 data", entries.get("services/lobby-1/plugins/LuckPerms/luckperms-h2-v2.mv.db"));
        List<String> command = runner.calls().getFirst().command();
        assertEquals("org.h2.tools.Script", command.get(3));
        assertTrue(command.get(5).startsWith("jdbc:h2:file:") && command.get(5).endsWith(";IFEXISTS=TRUE;ACCESS_MODE_DATA=r"),
                command.get(5));
        assertEquals("per-service", info.manifest().orElseThrow().database().status());

        // Running: no SCRIPT TO, only the files, with a warning.
        test.control.state("lobby-1", State.RUNNING);
        BackupInfo running = service(DatabaseMode.H2).create(BackupRequest.all(BackupReason.MANUAL));
        assertEquals(1, runner.calls().size());
        assertFalse(TestHome.entries(running.file()).containsKey("database/lobby-1-luckperms-h2.sql"));
        assertTrue(running.warnings().stream().anyMatch(w -> w.contains("H2")), running.warnings().toString());
    }

    @Test
    void restoreNeverTouchesTheDatabase() throws Exception {
        BackupService service = service(DatabaseMode.MANAGED);
        BackupInfo info = service.create(BackupRequest.of(BackupReason.MANUAL, Set.of("lobby-1")));
        int dumps = runner.calls().size();
        service.restore(info.id(), "lobby-1");
        assertEquals(dumps, runner.calls().size(), "the safety backup before a restore has no database dump");
        assertEquals("level-lobby-1", Files.readString(lobby.resolve("world/level.dat"), StandardCharsets.UTF_8));
    }
}
