package net.vectora.cloud.database.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.cloud.database.DatabaseEndpoint;
import net.vectora.cloud.database.DatabaseException;
import net.vectora.cloud.database.DatabaseProcesses;
import net.vectora.cloud.database.DatabaseSecrets;
import net.vectora.cloud.database.FakeSqlServer;
import net.vectora.cloud.database.StorageMethod;
import net.vectora.cloud.database.sql.TlsMode;
import net.vectora.cloud.download.provider.TargetPlatform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedMariaDbProvisionerTest {

    private static final String ROOT_PW = "RootPasswordForTests0123";
    private static final String APP_PW = "AppPasswordForTests45678";
    private static final DatabaseSecrets SECRETS = new DatabaseSecrets(Secret.of(ROOT_PW), Secret.of(APP_PW));
    private static final TargetPlatform WINDOWS =
            new TargetPlatform(TargetPlatform.Os.WINDOWS, TargetPlatform.Arch.X64, TargetPlatform.Libc.NONE);
    private static final DatabaseConfig CONFIG =
            new DatabaseConfig(DatabaseMode.MANAGED, "0.0.0.0", 3317, "luckperms", "luckperms", null);
    private static final ManagedMariaDbProvisioner.Timeouts FAST = new ManagedMariaDbProvisioner.Timeouts(
            Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(2));

    @TempDir
    Path home;
    Path base;
    MariaDbLayout layout;
    final FakeSqlServer server = new FakeSqlServer();
    final FakeProcesses processes = new FakeProcesses();

    @BeforeEach
    void setUp() throws IOException {
        base = home.resolve("mariadb-11.8.9-winx64");
        Files.createDirectories(base.resolve("bin"));
        Files.writeString(base.resolve("bin/mariadbd.exe"), "");
        Files.writeString(base.resolve("bin/mariadb-install-db.exe"), "");
        layout = new MariaDbLayout(home);
        server.up = false;
        server.accounts.add(List.of("root", "localhost"));
        server.accounts.add(List.of("", "localhost"));
        server.accounts.add(List.of("root", "build-host"));
    }

    private ManagedMariaDbProvisioner provisioner(TargetPlatform system) {
        return new ManagedMariaDbProvisioner(CONFIG, SECRETS, layout, platform -> base, processes, server, system, FAST);
    }

    /** Pretends to be mariadb-install-db and mariadbd. */
    final class FakeProcesses implements DatabaseProcesses {
        final List<List<String>> runs = new ArrayList<>();
        final List<List<String>> starts = new ArrayList<>();
        String initFileSeenAtStart;
        int initExit;
        boolean crashOnStart;
        FakeRunning current;

        @Override
        public int run(Command command, Duration timeout) throws IOException {
            runs.add(command.arguments());
            if (initExit == 0) {
                Files.createDirectories(layout.dataDir().resolve("mysql"));
            }
            return initExit;
        }

        @Override
        public Running start(Command command) throws IOException {
            starts.add(command.arguments());
            String init = command.arguments().stream().filter(a -> a.startsWith("--init-file=")).findFirst().orElse(null);
            if (init != null) {
                Path file = Path.of(init.substring("--init-file=".length()));
                initFileSeenAtStart = Files.readString(file);
                if (initFileSeenAtStart.contains("IDENTIFIED BY '" + ROOT_PW + "'")) {
                    server.passwords.put("root", ROOT_PW);
                }
            }
            if (!crashOnStart) {
                server.up = true;
            } else {
                Files.writeString(command.logFile(), "[ERROR] Can't start server: Bind on TCP/IP port\n");
            }
            current = new FakeRunning(!crashOnStart);
            return current;
        }
    }

    final class FakeRunning implements DatabaseProcesses.Running {
        boolean alive;
        boolean killed;

        FakeRunning(boolean alive) {
            this.alive = alive;
        }

        @Override
        public boolean isAlive() {
            return alive && server.up;
        }

        @Override
        public boolean waitFor(Duration timeout) {
            return !isAlive();
        }

        @Override
        public void kill() {
            killed = true;
            alive = false;
            server.up = false;
        }
    }

    @Test
    void firstStartInitialisesSecuresAndCreatesTheUser() throws Exception {
        ManagedMariaDbProvisioner p = provisioner(WINDOWS);
        DatabaseEndpoint endpoint = p.provision();

        assertEquals(StorageMethod.MARIADB, endpoint.storageMethod());
        assertEquals("127.0.0.1", endpoint.host(), "always loopback, whatever database.host says");
        assertEquals(3317, endpoint.port());
        assertEquals(1, processes.runs.size());
        assertEquals(List.of(base.resolve("bin/mariadb-install-db.exe").toString(), "--datadir=" + layout.dataDir()),
                processes.runs.get(0));
        assertTrue(processes.starts.get(0).contains("--init-file=" + layout.initFile()));
        assertTrue(processes.initFileSeenAtStart.contains("DELETE FROM mysql.global_priv WHERE User = '';"));
        assertFalse(Files.exists(layout.initFile()), "the init file with the root password is deleted");

        String ini = Files.readString(layout.configFile());
        assertTrue(ini.contains("bind-address=127.0.0.1\n"));
        assertTrue(ini.contains("max_connections=150\n"));
        assertTrue(ini.contains("port=3317\n"));

        List<String> root = server.statementsOf("root");
        assertTrue(root.contains("DROP USER IF EXISTS ''@'localhost'"), root.toString());
        assertTrue(root.contains("DROP USER IF EXISTS 'root'@'build-host'"), root.toString());
        assertFalse(root.contains("DROP USER IF EXISTS 'root'@'localhost'"));
        assertTrue(root.contains("CREATE USER IF NOT EXISTS 'luckperms'@'127.0.0.1' IDENTIFIED BY '" + APP_PW + "'"));
        assertTrue(server.targets.stream().allMatch(t -> t.tls() == TlsMode.DISABLE && t.host().equals("127.0.0.1")));
    }

    @Test
    void secondStartSkipsInitialisationAndIsIdempotent() throws Exception {
        ManagedMariaDbProvisioner p = provisioner(WINDOWS);
        p.provision();
        server.passwords.put("luckperms", APP_PW);
        p.shutdown();
        assertTrue(server.statementsOf("root").contains("SHUTDOWN"));
        assertFalse(server.up);
        List<String> firstRun = server.statementsOf("root");

        server.log.clear();
        DatabaseEndpoint again = p.provision();
        assertEquals(1, processes.runs.size(), "mariadb-install-db runs only once");
        assertEquals(2, processes.starts.size());
        assertTrue(processes.starts.get(1).stream().noneMatch(a -> a.startsWith("--init-file=")));
        assertEquals(firstRun.subList(0, firstRun.size() - 1), server.statementsOf("root"),
                "the same setup statements run again");
        assertEquals("127.0.0.1", again.host());
    }

    @Test
    void aServerThatAlreadyAnswersIsTakenOver() throws Exception {
        server.up = true;
        server.passwords.put("root", ROOT_PW);
        ManagedMariaDbProvisioner p = provisioner(WINDOWS);
        p.provision();
        assertTrue(processes.runs.isEmpty());
        assertTrue(processes.starts.isEmpty());
        p.shutdown();
        assertFalse(server.up);
    }

    @Test
    void otherPlatformsGetAClearHint() {
        TargetPlatform mac = new TargetPlatform(TargetPlatform.Os.MAC, TargetPlatform.Arch.AARCH64, TargetPlatform.Libc.NONE);
        DatabaseException e = assertThrows(DatabaseException.class, () -> provisioner(mac).provision());
        assertEquals(DatabaseException.Reason.MANAGED_PLATFORM, e.reason());
        assertTrue(e.getMessage().contains("'external'") && e.getMessage().contains("'h2'"), e.getMessage());
        assertTrue(processes.starts.isEmpty());
    }

    @Test
    void failedInitialisationReportsTheExitCode() {
        processes.initExit = 3;
        DatabaseException e = assertThrows(DatabaseException.class, () -> provisioner(WINDOWS).provision());
        assertEquals(DatabaseException.Reason.INITIALIZE, e.reason());
        assertTrue(e.getMessage().contains("exit code 3"));
        assertTrue(processes.starts.isEmpty());
    }

    @Test
    void foreignDataDirectoryIsNeverOverwritten() throws IOException {
        Files.createDirectories(layout.dataDir());
        Files.writeString(layout.dataDir().resolve("something.txt"), "keep me");
        DatabaseException e = assertThrows(DatabaseException.class, () -> provisioner(WINDOWS).provision());
        assertEquals(DatabaseException.Reason.INITIALIZE, e.reason());
        assertTrue(processes.runs.isEmpty());
        assertEquals("keep me", Files.readString(layout.dataDir().resolve("something.txt")));
    }

    @Test
    void crashRightAfterStartShowsTheLog() {
        processes.crashOnStart = true;
        DatabaseException e = assertThrows(DatabaseException.class, () -> provisioner(WINDOWS).provision());
        assertEquals(DatabaseException.Reason.START, e.reason());
        assertTrue(e.getMessage().contains("Bind on TCP/IP port"), e.getMessage());
        assertFalse(Files.exists(layout.initFile()), "init file is deleted on failure too");
    }

    @Test
    void failedFirstStartRunsTheInitFileAgainOnTheNextStart() throws Exception {
        processes.crashOnStart = true;
        assertThrows(DatabaseException.class, () -> provisioner(WINDOWS).provision());
        assertTrue(layout.initialized(), "mariadb-install-db ran");
        assertTrue(layout.bootstrapPending(), "root has no password yet");
        assertFalse(Files.exists(layout.initFile()));

        processes.crashOnStart = false;
        provisioner(WINDOWS).provision();
        assertEquals(1, processes.runs.size(), "the data directory is not created twice");
        assertTrue(processes.starts.get(1).contains("--init-file=" + layout.initFile()),
                "the second start sets the root password");
        assertFalse(layout.bootstrapPending());
        assertFalse(Files.exists(layout.initFile()));
    }

    @Test
    void anExistingDataDirectoryWithoutMarkerGetsNoInitFile() throws Exception {
        Files.createDirectories(layout.dataDir().resolve("mysql"));
        server.passwords.put("root", ROOT_PW);
        provisioner(WINDOWS).provision();
        assertTrue(processes.runs.isEmpty());
        assertTrue(processes.starts.get(0).stream().noneMatch(a -> a.startsWith("--init-file=")));
    }

    @Test
    void wrongRootPasswordStopsTheServerAndExplains() throws IOException {
        Files.createDirectories(layout.dataDir().resolve("mysql"));
        server.passwords.put("root", "AnotherRootPasswordXYZ12");
        DatabaseException e = assertThrows(DatabaseException.class, () -> provisioner(WINDOWS).provision());
        assertEquals(DatabaseException.Reason.LOGIN, e.reason());
        assertTrue(processes.current.killed);
        assertFalse(e.getMessage().contains(ROOT_PW));
    }

    @Test
    void failingSetupStatementHidesThePasswords() {
        server.failing.add("CREATE USER IF NOT EXISTS 'luckperms'");
        DatabaseException e = assertThrows(DatabaseException.class, () -> provisioner(WINDOWS).provision());
        assertEquals(DatabaseException.Reason.SETUP, e.reason());
        assertFalse(e.getMessage().contains(APP_PW), e.getMessage());
        assertFalse(e.getMessage().contains(ROOT_PW), e.getMessage());
    }

    @Test
    void emptyOrUnusablePasswordsAreRefusedBeforeAnythingRuns() {
        ManagedMariaDbProvisioner empty = new ManagedMariaDbProvisioner(CONFIG,
                new DatabaseSecrets(Secret.of(""), Secret.of(APP_PW)), layout, platform -> base, processes, server,
                WINDOWS, FAST);
        assertEquals(DatabaseException.Reason.SETTINGS, assertThrows(DatabaseException.class, empty::provision).reason());
        ManagedMariaDbProvisioner backslash = new ManagedMariaDbProvisioner(CONFIG,
                new DatabaseSecrets(Secret.of("abc\\def"), Secret.of(APP_PW)), layout, platform -> base, processes,
                server, WINDOWS, FAST);
        assertEquals(DatabaseException.Reason.SETTINGS,
                assertThrows(DatabaseException.class, backslash::provision).reason());
        assertTrue(processes.runs.isEmpty() && processes.starts.isEmpty());
        assertEquals(0, server.attempts);
    }

    @Test
    void shutdownWithoutStartDoesNothing() throws Exception {
        provisioner(WINDOWS).shutdown();
        assertEquals(0, server.attempts);
    }

    @Test
    void initFileIsOwnerOnly() throws IOException {
        Path file = home.resolve("secret.sql");
        ManagedMariaDbProvisioner.writeOwnerOnly(file, "x");
        assertTrue(net.vectora.cloud.config.secret.OwnerOnlyPermissions.isOwnerOnly(file));
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> ManagedMariaDbProvisioner.writeOwnerOnly(file, "y"));
    }
}
