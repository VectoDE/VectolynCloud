package net.vectora.cloud.database.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.secret.GeneratedSecret;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.cloud.config.secret.SecretGenerator;
import net.vectora.cloud.database.DatabaseEndpoint;
import net.vectora.cloud.database.DatabaseSecrets;
import net.vectora.cloud.database.StorageMethod;
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
 * Downloads the real MariaDB server, sets it up in a temp folder on a free port, checks what the
 * setup promises and stops it again. Never touches an existing database. Tagged {@code mariadb}:
 * runs only through {@code ./gradlew :VectoraCloud:cloud-database:mariadbTest} (weekly in CI).
 */
@Tag("mariadb")
class ManagedMariaDbIT {

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
    void managedServerLifecycle() throws Exception {
        SecretGenerator generator = new SecretGenerator();
        Secret rootPassword = generator.generate(GeneratedSecret.DB_ROOT_PASSWORD);
        Secret appPassword = generator.generate(GeneratedSecret.DB_PASSWORD);
        int port = freePort();
        DatabaseConfig config = new DatabaseConfig(DatabaseMode.MANAGED, "127.0.0.1", port, "luckperms", "luckperms", null);

        Path cache = Path.of(System.getProperty("vectora.mariadb.cache", home.resolve("cache").toString()));
        DownloadClient client = DownloadClient.createDefault();
        ProviderRegistry registry = ProviderRegistry.standard(client);
        ArtifactDownloader downloader = new ArtifactDownloader(client, registry, new ArtifactCache(cache));
        MariaDbBinaries binaries = new DownloadingMariaDbBinaries(registry.get("mariadb"), downloader,
                new SafeExtractor(ExtractionLimits.defaults()), home, System.getProperty("vectora.mariadb.version"));
        JdbcConnector connector = new JdbcConnector();
        provisioner = new ManagedMariaDbProvisioner(config, new DatabaseSecrets(rootPassword, appPassword),
                new MariaDbLayout(home), binaries, new LocalProcesses(), connector, TargetPlatform.current(),
                ManagedMariaDbProvisioner.Timeouts.defaults());

        // First start: download, initialise, secure, create.
        DatabaseEndpoint endpoint = provisioner.provision();
        assertEquals(StorageMethod.MARIADB, endpoint.storageMethod());
        assertTrue(endpoint.version().orElseThrow().mariaDb());
        assertFalse(Files.exists(home.resolve(MariaDbLayout.INIT_FILE)), "init file must be gone");
        ConnectionTarget target = ConnectionTarget.of("127.0.0.1", port);

        try (SqlSession root = connector.open(target, "root", rootPassword)) {
            assertEquals(List.of(List.of("127.0.0.1")), root.query("SELECT @@bind_address"));
            assertEquals(List.of(List.of("150")), root.query("SELECT @@max_connections"));
            assertEquals(List.of(List.of("0")), root.query("SELECT COUNT(*) FROM mysql.user WHERE User = ''"));
            assertEquals(List.of(List.of("0")), root.query(
                    "SELECT COUNT(*) FROM mysql.user WHERE User = 'root' AND Host NOT IN ('localhost', '127.0.0.1', '::1')"));
            assertEquals(List.of(), root.query("SHOW DATABASES LIKE 'test'"));
            String grants = root.query("SHOW GRANTS FOR 'luckperms'@'127.0.0.1'").toString();
            assertTrue(grants.contains("GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, REFERENCES, INDEX, ALTER ON `luckperms`.*"),
                    grants);
            assertFalse(grants.contains("ALL PRIVILEGES"), grants);
        }

        // Counter-checks: wrong passwords and rights outside the own database fail.
        assertThrows(SQLException.class, () -> connector.open(target, "root", Secret.of("wrong-password-123456")).close());
        assertThrows(SQLException.class, () -> connector.open(target, "", Secret.of("")).close());
        try (SqlSession app = connector.open(target, "luckperms", appPassword)) {
            app.execute("CREATE TABLE `luckperms`.`probe` (id INT PRIMARY KEY)");
            app.execute("INSERT INTO `luckperms`.`probe` VALUES (1)");
            assertEquals(List.of(List.of("1")), app.query("SELECT id FROM `luckperms`.`probe`"));
            assertThrows(SQLException.class, () -> app.execute("CREATE DATABASE other"));
            assertThrows(SQLException.class, () -> app.query("SELECT * FROM mysql.user"));
            assertThrows(SQLException.class, () -> app.execute("CREATE USER 'x'@'%' IDENTIFIED BY 'xxxxxxxxxxxxxxxxx'"));
        }
        assertTrue(provisioner.health().healthy());

        // Clean stop, then a second start without initialisation; data survives.
        provisioner.shutdown();
        assertFalse(portOpen(port), "server must be gone after shutdown");
        assertFalse(provisioner.health().healthy());

        provisioner.provision();
        try (SqlSession app = connector.open(target, "luckperms", appPassword)) {
            assertEquals(List.of(List.of("1")), app.query("SELECT id FROM `luckperms`.`probe`"));
        }
        String log = Files.readString(home.resolve("mariadb.log"));
        assertFalse(log.contains(rootPassword.reveal()), "the log must not contain the root password");
        assertFalse(log.contains(appPassword.reveal()), "the log must not contain the application password");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean portOpen(int port) {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            return socket.isConnected();
        } catch (IOException e) {
            return false;
        }
    }
}
