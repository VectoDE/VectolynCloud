package net.vectora.cloud.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import net.vectora.cloud.backup.database.DatabaseAccess;
import net.vectora.cloud.backup.database.ProcessToolRunner;
import net.vectora.cloud.backup.database.ToolRunner;
import net.vectora.cloud.backup.manifest.BackupManifest;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.secret.EnvFileParser;
import net.vectora.cloud.config.secret.GeneratedSecret;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.cloud.config.secret.SecretGenerator;
import net.vectora.cloud.database.DatabaseSecrets;
import net.vectora.cloud.database.managed.DownloadingMariaDbBinaries;
import net.vectora.cloud.database.managed.ManagedMariaDbProvisioner;
import net.vectora.cloud.database.managed.MariaDbBinaries;
import net.vectora.cloud.database.managed.MariaDbLayout;
import net.vectora.cloud.database.sql.ConnectionTarget;
import net.vectora.cloud.database.sql.JdbcConnector;
import net.vectora.cloud.database.sql.SqlSession;
import net.vectora.cloud.download.archive.ExtractionLimits;
import net.vectora.cloud.download.archive.SafeExtractor;
import net.vectora.cloud.download.cache.ArtifactCache;
import net.vectora.cloud.download.cache.ArtifactDownloader;
import net.vectora.cloud.download.http.DownloadClient;
import net.vectora.cloud.download.provider.ProviderRegistry;
import net.vectora.cloud.download.provider.TargetPlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Dumps a throwaway managed MariaDB with the real {@code mariadb-dump}: the server is downloaded
 * and set up in a temp folder on a free port, exactly like cloud-database's ManagedMariaDbIT, and
 * stopped afterwards. Never touches an existing database. Tagged {@code mariadb}: runs only through
 * {@code ./gradlew :VectoraCloud:cloud-backup:mariadbTest} (weekly and on pull requests in CI).
 */
@Tag("mariadb")
class ManagedMariaDbBackupIT {

    @TempDir
    Path database;

    @TempDir
    Path home;

    private ManagedMariaDbProvisioner provisioner;

    @AfterEach
    void stop() throws InterruptedException {
        if (provisioner != null) {
            provisioner.shutdown();
        }
    }

    @Test
    void managedDatabaseIsDumpedWithoutThePasswordOnTheCommandLine() throws Exception {
        SecretGenerator generator = new SecretGenerator();
        Secret rootPassword = generator.generate(GeneratedSecret.DB_ROOT_PASSWORD);
        Secret appPassword = generator.generate(GeneratedSecret.DB_PASSWORD);
        int port = freePort();
        DatabaseConfig config = new DatabaseConfig(DatabaseMode.MANAGED, "127.0.0.1", port, "luckperms", "luckperms",
                null);

        Path cache = Path.of(System.getProperty("vectora.mariadb.cache", database.resolve("cache").toString()));
        DownloadClient client = DownloadClient.createDefault();
        ProviderRegistry registry = ProviderRegistry.standard(client);
        ArtifactDownloader downloader = new ArtifactDownloader(client, registry, new ArtifactCache(cache));
        MariaDbBinaries downloading = new DownloadingMariaDbBinaries(registry.get("mariadb"), downloader,
                new SafeExtractor(ExtractionLimits.defaults()), database, System.getProperty("vectora.mariadb.version"));
        Path[] base = new Path[1];
        MariaDbBinaries binaries = platform -> base[0] = downloading.install(platform);
        JdbcConnector connector = new JdbcConnector();
        provisioner = new ManagedMariaDbProvisioner(config, new DatabaseSecrets(rootPassword, appPassword),
                new MariaDbLayout(database), binaries, new LocalProcesses(), connector, TargetPlatform.current(),
                ManagedMariaDbProvisioner.Timeouts.defaults());
        provisioner.provision();

        try (SqlSession app = connector.open(ConnectionTarget.of("127.0.0.1", port), "luckperms", appPassword)) {
            app.execute("CREATE TABLE `luckperms`.`luckperms_players` (uuid VARCHAR(36) PRIMARY KEY, username VARCHAR(16))");
            app.execute("INSERT INTO `luckperms`.`luckperms_players` VALUES ('0f5b1d6c-backup-it', 'BackupProbe')");
        }

        TestHome test = new TestHome(home);
        test.addServer("lobby-1", "lobby", "Lobby-Server", true);
        DatabaseAccess access = DatabaseAccess.of(config, EnvFileParser.parse(GeneratedSecret.DB_ROOT_PASSWORD.key() + "="
                + rootPassword.reveal() + "\n" + GeneratedSecret.DB_PASSWORD.key() + "=" + appPassword.reveal() + "\n"),
                base[0]);
        RecordingRunner runner = new RecordingRunner();
        // The real search: .exe names on Windows.
        boolean windows = TargetPlatform.current().os() == TargetPlatform.Os.WINDOWS;
        BackupService service = test.builder().database(() -> Optional.of(access)).tools(runner)
                .searchPath(null, windows).build();

        BackupInfo info = service.create(BackupRequest.all(BackupReason.MANUAL));

        BackupManifest.DatabaseEntry entry = info.manifest().orElseThrow().database();
        assertEquals("dumped", entry.status(), entry.detail());
        Map<String, String> entries = TestHome.entries(info.file());
        String dump = entries.get("database/luckperms.sql");
        assertTrue(dump.contains("CREATE TABLE `luckperms_players`"), "table is in the dump");
        assertTrue(dump.contains("BackupProbe"), "row is in the dump");
        assertTrue(dump.stripTrailing().lines().reduce((a, b) -> b).orElseThrow().startsWith("-- Dump completed"));
        assertEquals(1, runner.commands.size());
        for (String argument : runner.commands.getFirst()) {
            assertFalse(argument.contains(rootPassword.reveal()), "password is never a process argument");
        }
        for (String content : entries.values()) {
            assertFalse(content.contains(rootPassword.reveal()), "no password in the archive");
            assertFalse(content.contains(appPassword.reveal()), "no password in the archive");
        }
        assertWorkFolderEmpty();

        // Counter-check: a wrong password fails the strict backup, and the option file is still gone.
        DatabaseAccess wrong = new DatabaseAccess(DatabaseMode.MANAGED, "127.0.0.1", port, "luckperms", "root",
                Secret.of("wrong-password-1234567890"), base[0]);
        BackupService failing = test.builder().database(() -> Optional.of(wrong)).tools(runner)
                .searchPath(null, windows).build();
        BackupException e = assertThrows(BackupException.class,
                () -> failing.createBeforeVersionChange("lobby", "26.3", "26.4"));
        assertEquals(BackupException.Reason.DATABASE, e.reason());
        assertFalse(e.getMessage().contains("wrong-password-1234567890"), e.getMessage());
        assertWorkFolderEmpty();
    }

    private void assertWorkFolderEmpty() throws IOException {
        try (Stream<Path> work = Files.list(home.resolve("temp/backup"))) {
            assertEquals(List.of(), work.toList(), "dump and option file are cleaned up");
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Runs the real tool and remembers each command line. */
    private static final class RecordingRunner implements ToolRunner {

        final List<List<String>> commands = new CopyOnWriteArrayList<>();
        private final ProcessToolRunner real = new ProcessToolRunner();

        @Override
        public Result run(List<String> command, Path workingDirectory, Duration timeout)
                throws IOException, InterruptedException {
            commands.add(List.copyOf(command));
            return real.run(command, workingDirectory, timeout);
        }
    }
}
