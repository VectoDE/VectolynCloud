package net.vectolyn.cloud.backup.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.vectolyn.cloud.backup.BackupException;
import net.vectolyn.cloud.config.model.DatabaseConfig;
import net.vectolyn.cloud.config.model.DatabaseMode;
import net.vectolyn.cloud.config.secret.EnvFile;
import net.vectolyn.cloud.config.secret.EnvFileParser;
import net.vectolyn.cloud.config.secret.Secret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MariaDbDumperTest {

    private static final String ROOT_PASSWORD = "RootPassword1234567890abcdefXYZ";
    private static final String APP_PASSWORD = "AppPassword1234567890abcdefXYZ";

    @TempDir
    Path dir;

    static EnvFile secrets() throws Exception {
        return EnvFileParser.parse(("VECTOLYN_DB_ROOT_PASSWORD=" + ROOT_PASSWORD + "\nVECTOLYN_DB_PASSWORD=" + APP_PASSWORD
                + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private DatabaseAccess managed() throws Exception {
        return DatabaseAccess.of(DatabaseConfig.defaults(), secrets(), dir.resolve("mariadb"));
    }

    private DatabaseAccess external() throws Exception {
        return DatabaseAccess.of(new DatabaseConfig(DatabaseMode.EXTERNAL, "db.example.org", 3306, "luckperms",
                "luckperms", null), secrets(), null);
    }

    @Test
    void accessUsesRootOnlyForTheManagedServer() throws Exception {
        DatabaseAccess managed = managed();
        assertEquals("root", managed.user());
        assertEquals(ROOT_PASSWORD, managed.password().reveal());
        DatabaseAccess external = external();
        assertEquals("luckperms", external.user());
        assertEquals(APP_PASSWORD, external.password().reveal());
        assertFalse(managed.toString().contains(ROOT_PASSWORD), managed.toString());
        DatabaseAccess docker = DatabaseAccess.of(DatabaseConfig.defaults().withMode(DatabaseMode.DOCKER), secrets(), null);
        assertTrue(docker.password().isEmpty());
    }

    @Test
    void passwordIsInTheOptionFileAndNeverAnArgument() throws Exception {
        FakeToolRunner runner = new FakeToolRunner();
        Path tool = Files.createFile(dir.resolve("mariadb-dump"));
        Path result = dir.resolve("dump.sql");
        Path work = Files.createDirectory(dir.resolve("work"));

        new MariaDbDumper(runner, Duration.ofMinutes(1)).dump(managed(), tool, result, work);

        FakeToolRunner.Call call = runner.calls().getFirst();
        for (String argument : call.command()) {
            assertFalse(argument.contains(ROOT_PASSWORD), argument);
            assertFalse(argument.startsWith("-p") || argument.startsWith("--password"), argument);
        }
        assertTrue(call.command().get(1).startsWith("--defaults-extra-file="), "must be the first option");
        assertTrue(call.optionFileContent().contains("password=\"" + ROOT_PASSWORD + "\""));
        assertTrue(call.optionFileContent().contains("user=root"));
        assertTrue(call.optionFileOwnerOnly(), "option file is owner-only while the tool runs");
        assertTrue(call.command().containsAll(List.of("--single-transaction", "--routines", "--events", "--triggers",
                "--databases", "luckperms")));
        assertTrue(Files.readString(result).contains("-- Dump completed"));
        try (Stream<Path> left = Files.list(work)) {
            assertEquals(List.of(), left.toList(), "option file and its folder are gone");
        }
    }

    @Test
    void externalServerIsDumpedAsTheApplicationUserWithoutRoutines() throws Exception {
        FakeToolRunner runner = new FakeToolRunner();
        Path tool = Files.createFile(dir.resolve("mysqldump"));
        new MariaDbDumper(runner, Duration.ofMinutes(1)).dump(external(), tool, dir.resolve("d.sql"), dir);
        FakeToolRunner.Call call = runner.calls().getFirst();
        assertTrue(call.optionFileContent().contains("user=luckperms"));
        assertTrue(call.optionFileContent().contains("host=db.example.org"));
        assertFalse(call.command().contains("--routines"));
        assertTrue(call.command().contains("--no-tablespaces"));
    }

    @Test
    void failureIsRedactedAndTheOptionFileIsStillDeleted() throws Exception {
        FakeToolRunner runner = new FakeToolRunner().exitCode(2, "Access denied for user 'root' using password "
                + ROOT_PASSWORD);
        Path tool = Files.createFile(dir.resolve("mariadb-dump"));
        Path work = Files.createDirectory(dir.resolve("work"));
        BackupException e = assertThrows(BackupException.class,
                () -> new MariaDbDumper(runner, Duration.ofMinutes(1)).dump(managed(), tool, dir.resolve("d.sql"), work));
        assertEquals(BackupException.Reason.DATABASE, e.reason());
        assertTrue(e.getMessage().contains("exit code 2"), e.getMessage());
        assertFalse(e.getMessage().contains(ROOT_PASSWORD), e.getMessage());
        try (Stream<Path> left = Files.list(work)) {
            assertEquals(List.of(), left.toList());
        }
    }

    @Test
    void incompleteDumpIsAFailure() throws Exception {
        FakeToolRunner runner = new FakeToolRunner().dump("-- MariaDB dump\nCREATE TABLE half");
        Path tool = Files.createFile(dir.resolve("mariadb-dump"));
        BackupException e = assertThrows(BackupException.class,
                () -> new MariaDbDumper(runner, Duration.ofMinutes(1)).dump(managed(), tool, dir.resolve("d.sql"), dir));
        assertTrue(e.getMessage().contains("Dump completed"), e.getMessage());
    }

    @Test
    void databaseNameThatLooksLikeAnOptionIsRefused() {
        DatabaseAccess access = new DatabaseAccess(DatabaseMode.EXTERNAL, "127.0.0.1", 3306, "--all-databases", "u",
                Secret.of("password-123456"), null);
        assertThrows(IllegalArgumentException.class,
                () -> MariaDbDumper.arguments(access, dir.resolve("t"), dir.resolve("o"), dir.resolve("r")));
    }

    @Test
    void toolIsFoundInTheManagedServerOnPathOrAsConfigured() throws Exception {
        Path managedBin = Files.createDirectories(dir.resolve("mariadb/bin"));
        Files.createFile(managedBin.resolve("mariadb-dump.exe"));
        assertEquals(Optional.of(managedBin.resolve("mariadb-dump.exe")),
                MariaDbDumper.findExecutable(managed(), null, null, true));
        assertEquals(Optional.empty(), MariaDbDumper.findExecutable(managed(), null, null, false), "no .exe on Linux");

        Path first = Files.createDirectories(dir.resolve("p1"));
        Path second = Files.createDirectories(dir.resolve("p2"));
        Files.createFile(second.resolve("mysqldump"));
        String path = "relative" + File.pathSeparator + first + File.pathSeparator + second;
        assertEquals(Optional.of(second.resolve("mysqldump")), MariaDbDumper.findExecutable(external(), null, path, false));
        Files.createFile(first.resolve("mariadb-dump"));
        assertEquals(Optional.of(first.resolve("mariadb-dump")), MariaDbDumper.findExecutable(external(), null, path, false),
                "mariadb-dump wins over mysqldump");

        Path configured = Files.createFile(dir.resolve("custom-dump"));
        assertEquals(Optional.of(configured), MariaDbDumper.findExecutable(external(), configured, path, false));
        assertEquals(Optional.empty(), MariaDbDumper.findExecutable(external(), dir.resolve("missing"), path, false));
    }

    @Test
    void environmentOfTheToolHasNoSecrets() {
        java.util.Map<String, String> env = new java.util.HashMap<>(java.util.Map.of("PATH", "/usr/bin",
                "VECTOLYN_DB_PASSWORD", "x", "vectolyn_api_token", "y", "MYSQL_PWD", "z", "MYSQL_HOST", "h", "HOME", "/home"));
        ProcessToolRunner.clean(env);
        assertEquals(java.util.Set.of("PATH", "HOME"), env.keySet());
    }
}
