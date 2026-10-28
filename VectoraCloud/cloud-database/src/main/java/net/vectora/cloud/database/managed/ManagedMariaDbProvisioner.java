package net.vectora.cloud.database.managed;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.secret.OwnerOnlyPermissions;
import net.vectora.cloud.config.secret.RedactingFilter;
import net.vectora.cloud.database.DatabaseEndpoint;
import net.vectora.cloud.database.DatabaseException;
import net.vectora.cloud.database.DatabaseException.Reason;
import net.vectora.cloud.database.DatabaseProcesses;
import net.vectora.cloud.database.DatabaseProvisioner;
import net.vectora.cloud.database.DatabaseSecrets;
import net.vectora.cloud.database.HealthReport;
import net.vectora.cloud.database.ServerSetup;
import net.vectora.cloud.database.StorageMethod;
import net.vectora.cloud.database.sql.BootstrapStatements;
import net.vectora.cloud.database.sql.ConnectionTarget;
import net.vectora.cloud.database.sql.ServerVersion;
import net.vectora.cloud.database.sql.SqlConnector;
import net.vectora.cloud.database.sql.SqlSession;
import net.vectora.cloud.database.sql.TlsMode;
import net.vectora.cloud.download.provider.TargetPlatform;

/**
 * Mode {@code managed}: the cloud downloads MariaDB and runs it on this machine.
 *
 * <ol>
 *   <li>Checks the platform (Windows x64, Linux x86_64 with glibc), see {@link ManagedPlatform}.</li>
 *   <li>If a server with the configured root password already answers on the port, it is used
 *       as is (for example after the cloud itself was restarted).</li>
 *   <li>Unpacks the server ({@link MariaDbBinaries}) and writes {@code vectora-mariadb.ini}
 *       ({@link MariaDbConfigFile}: bound to 127.0.0.1, {@code max_connections} 150).</li>
 *   <li>On the first start only: creates the data directory with {@code mariadb-install-db} and
 *       passes an {@code --init-file} that sets the generated root password and removes anonymous
 *       accounts and the {@code test} database before the server accepts its first connection.
 *       The file is readable by the owner only and deleted right after the start. A marker
 *       ({@link MariaDbLayout#bootstrapPending()}) keeps the init file in use on later starts until
 *       {@code root} logged in once, so a failed first start never leaves a root without password.</li>
 *   <li>Starts the server through {@link DatabaseProcesses} and waits until {@code root} can log in.</li>
 *   <li>Runs the setup as {@code root} (again on every start, all idempotent): secure, create the
 *       application database and user with minimal rights, set its password.</li>
 *   <li>Checks that the application user can log in.</li>
 * </ol>
 *
 * <p>{@link #shutdown()} sends {@code SHUTDOWN} as {@code root} and waits for the process to end;
 * only if that fails is the process killed.
 */
public final class ManagedMariaDbProvisioner implements DatabaseProvisioner {

    /** Host part of the application user: the servers connect over TCP from this machine. */
    public static final String APPLICATION_HOST = "127.0.0.1";

    private static final int LOG_TAIL_LINES = 25;
    private static final int ACCESS_DENIED = 1045;

    /**
     * Time limits.
     *
     * @param initialize for {@code mariadb-install-db}
     * @param start until {@code root} can log in after the server was started
     * @param stop until the process ended after {@code SHUTDOWN}
     */
    public record Timeouts(Duration initialize, Duration start, Duration stop) {

        /**
         * Checks the values.
         *
         * @param initialize for {@code mariadb-install-db}
         * @param start for the start
         * @param stop for the stop
         */
        public Timeouts {
            Objects.requireNonNull(initialize, "initialize");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(stop, "stop");
        }

        /**
         * Five minutes to initialise, two to start, one to stop.
         *
         * @return the defaults
         */
        public static Timeouts defaults() {
            return new Timeouts(Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofMinutes(1));
        }
    }

    private final DatabaseConfig config;
    private final DatabaseSecrets secrets;
    private final MariaDbLayout layout;
    private final MariaDbBinaries binaries;
    private final DatabaseProcesses processes;
    private final SqlConnector connector;
    private final TargetPlatform system;
    private final Timeouts timeouts;
    private final ConnectionTarget target;

    private DatabaseProcesses.Running process;
    private boolean adopted;

    /**
     * Creates the provisioner.
     *
     * @param config database settings, mode {@code managed}; {@code host} is ignored, the server
     *     always listens on 127.0.0.1
     * @param secrets root and application password
     * @param layout files below the database home
     * @param binaries supplies the unpacked server
     * @param processes runs the programs (implemented by {@code cloud-node})
     * @param connector opens connections
     * @param system the platform the cloud runs on
     * @param timeouts time limits
     */
    public ManagedMariaDbProvisioner(DatabaseConfig config, DatabaseSecrets secrets, MariaDbLayout layout,
            MariaDbBinaries binaries, DatabaseProcesses processes, SqlConnector connector, TargetPlatform system,
            Timeouts timeouts) {
        this.config = Objects.requireNonNull(config, "config");
        if (config.mode() != DatabaseMode.MANAGED) {
            throw new IllegalArgumentException("not a managed database: " + config.mode());
        }
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.binaries = Objects.requireNonNull(binaries, "binaries");
        this.processes = Objects.requireNonNull(processes, "processes");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.system = Objects.requireNonNull(system, "system");
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
        this.target = new ConnectionTarget(MariaDbConfigFile.BIND_ADDRESS, config.port(), TlsMode.DISABLE,
                ConnectionTarget.DEFAULT_TIMEOUT);
    }

    @Override
    public DatabaseMode mode() {
        return DatabaseMode.MANAGED;
    }

    @Override
    public synchronized DatabaseEndpoint provision() throws DatabaseException, InterruptedException {
        ManagedPlatform platform = ManagedPlatform.of(system);
        checkSecrets();
        Optional<ServerVersion> version;
        try (SqlSession root = runningServer(platform)) {
            ServerSetup.secureAndCreate(root, config, APPLICATION_HOST, BootstrapStatements.LOCAL_ROOT_HOSTS, secrets);
            version = ServerVersion.parse(root.serverVersion());
        } catch (SQLException e) {
            throw new DatabaseException(Reason.SETUP, "Reading the server version failed: "
                    + ServerSetup.describe(e, redactor()), e);
        }
        DatabaseConfig local = new DatabaseConfig(DatabaseMode.MANAGED, MariaDbConfigFile.BIND_ADDRESS, config.port(),
                config.name(), config.user(), null);
        ServerSetup.checkApplicationLogin(connector, target, local, secrets.application());
        return new DatabaseEndpoint(StorageMethod.MARIADB, MariaDbConfigFile.BIND_ADDRESS, config.port(), config.name(),
                config.user(), version);
    }

    @Override
    public HealthReport health() {
        return ServerSetup.health(connector, target, config.user(), secrets.application());
    }

    @Override
    public synchronized void shutdown() throws InterruptedException {
        if (process == null && !adopted) {
            return;
        }
        try (SqlSession root = connector.open(target.withTimeout(Duration.ofSeconds(5)), "root", secrets.root())) {
            root.execute("SHUTDOWN");
        } catch (SQLException e) {
            // The server may close the connection while it shuts down; the wait below decides.
        }
        if (process != null) {
            if (!process.waitFor(timeouts.stop())) {
                process.kill();
                process.waitFor(Duration.ofSeconds(10));
            }
        } else {
            waitUntilUnreachable(timeouts.stop());
        }
        process = null;
        adopted = false;
    }

    /** Returns a root session to a running server, starting it first if needed. */
    private SqlSession runningServer(ManagedPlatform platform) throws DatabaseException, InterruptedException {
        if (process != null && process.isAlive() || adopted) {
            return openRoot(Duration.ofSeconds(10), () -> true);
        }
        Optional<SqlSession> already = tryRoot();
        if (already.isPresent()) {
            adopted = true;
            return already.get();
        }

        Path base;
        try {
            base = binaries.install(platform);
            Files.createDirectories(layout.home());
            Files.writeString(layout.configFile(), MariaDbConfigFile.render(config.port(), socket(platform)),
                    StandardCharsets.UTF_8);
            Files.deleteIfExists(layout.initFile());
        } catch (IOException e) {
            throw new DatabaseException(Reason.INSTALL, "Preparing the MariaDB folder " + layout.home() + " failed: "
                    + e.getMessage(), e);
        }

        if (!layout.initialized()) {
            initialize(platform, base);
        }
        // Set by initialize() and kept until root logged in once: a first start that failed leaves
        // a data directory whose root has no password yet, so the next start runs the init file again.
        boolean bootstrap = layout.bootstrapPending();
        try {
            if (bootstrap) {
                try {
                    writeOwnerOnly(layout.initFile(), BootstrapStatements.initFile(secrets.root()));
                } catch (IOException e) {
                    throw new DatabaseException(Reason.INITIALIZE, "Writing " + layout.initFile() + " failed: "
                            + e.getMessage(), e);
                }
            }
            try {
                process = processes.start(MariaDbCommands.server(platform, base, layout,
                        bootstrap ? Optional.of(layout.initFile()) : Optional.empty()));
            } catch (IOException e) {
                throw new DatabaseException(Reason.START, "Starting MariaDB failed: " + e.getMessage(), e);
            }
            DatabaseProcesses.Running started = process;
            SqlSession root = openRoot(timeouts.start(), started::isAlive);
            if (bootstrap) {
                try {
                    Files.deleteIfExists(layout.initPendingMarker());
                } catch (IOException e) {
                    // The init file is idempotent; the next start simply runs it once more.
                }
            }
            return root;
        } finally {
            if (bootstrap) {
                try {
                    Files.deleteIfExists(layout.initFile());
                } catch (IOException e) {
                    // Reported on the next start, which deletes it before anything else.
                }
            }
        }
    }

    private void initialize(ManagedPlatform platform, Path base) throws DatabaseException, InterruptedException {
        if (hasContent(layout.dataDir())) {
            throw new DatabaseException(Reason.INITIALIZE, "The data directory " + layout.dataDir() + " is not empty"
                    + " but holds no MariaDB database (no 'mysql' folder). Move it away; the cloud then creates a"
                    + " new one.");
        }
        try {
            Files.writeString(layout.initPendingMarker(), "The root password is set on the next start.\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DatabaseException(Reason.INITIALIZE, "Writing " + layout.initPendingMarker() + " failed: "
                    + e.getMessage(), e);
        }
        int exit;
        try {
            exit = processes.run(MariaDbCommands.initialize(platform, base, layout), timeouts.initialize());
        } catch (IOException e) {
            throw new DatabaseException(Reason.INITIALIZE, "mariadb-install-db failed: " + e.getMessage()
                    + logTail(), e);
        }
        if (exit != 0 || !layout.initialized()) {
            throw new DatabaseException(Reason.INITIALIZE, "mariadb-install-db ended with exit code " + exit
                    + " and created no database in " + layout.dataDir() + "." + hint() + logTail());
        }
    }

    private SqlSession openRoot(Duration timeout, java.util.function.BooleanSupplier alive)
            throws DatabaseException, InterruptedException {
        try {
            return ServerSetup.openWithRetry(connector, target, "root", secrets.root(), timeout, alive);
        } catch (SQLException e) {
            if (process != null && !process.isAlive()) {
                process = null;
                throw new DatabaseException(Reason.START, "MariaDB stopped right after the start." + hint()
                        + logTail(), e);
            }
            if (e.getErrorCode() == ACCESS_DENIED) {
                stopAfterFailedLogin();
                throw new DatabaseException(Reason.LOGIN, "MariaDB refuses VECTORA_DB_ROOT_PASSWORD from"
                        + " secrets/vectora.env. The data directory " + layout.dataDir() + " was set up with another"
                        + " root password. Put that password into secrets/vectora.env, or move the data directory"
                        + " away so the cloud creates a new one.", e);
            }
            stopAfterFailedLogin();
            throw new DatabaseException(Reason.UNREACHABLE, "MariaDB did not answer on " + target + " within "
                    + timeout.toSeconds() + " s: " + ServerSetup.describe(e, redactor()) + logTail(), e);
        }
    }

    private Optional<SqlSession> tryRoot() {
        try {
            return Optional.of(connector.open(target.withTimeout(Duration.ofSeconds(2)), "root", secrets.root()));
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    private void stopAfterFailedLogin() throws InterruptedException {
        if (process != null) {
            // Without the root password there is no clean SHUTDOWN.
            process.kill();
            process.waitFor(Duration.ofSeconds(10));
            process = null;
        }
    }

    private void waitUntilUnreachable(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() - deadline < 0) {
            Optional<SqlSession> session = tryRoot();
            if (session.isEmpty()) {
                return;
            }
            try {
                session.get().close();
            } catch (SQLException e) {
                // Closing a connection to a server that goes down may fail.
            }
            Thread.sleep(500);
        }
    }

    private void checkSecrets() throws DatabaseException {
        if (secrets.root().isEmpty() || secrets.application().isEmpty()) {
            throw new DatabaseException(Reason.SETTINGS, "VECTORA_DB_ROOT_PASSWORD and VECTORA_DB_PASSWORD in"
                    + " secrets/vectora.env must not be empty. The cloud generates both on the first start.");
        }
        try {
            BootstrapStatements.initFile(secrets.root());
            BootstrapStatements.application(config.name(), config.user(), APPLICATION_HOST, secrets.application());
        } catch (IllegalArgumentException e) {
            throw new DatabaseException(Reason.SETTINGS, "Database setting or password not usable: " + e.getMessage(),
                    e);
        }
    }

    /** Linux: a short socket path; the default under /run is often not writable for normal users. */
    private Optional<Path> socket(ManagedPlatform platform) {
        if (platform != ManagedPlatform.LINUX_X64_GLIBC) {
            return Optional.empty();
        }
        return Optional.of(Path.of(System.getProperty("java.io.tmpdir"), "vectora-mariadb-" + config.port() + ".sock"));
    }

    private String hint() {
        if (system.os() == TargetPlatform.Os.LINUX) {
            return " On Linux the MariaDB build needs the system libraries libaio and libncurses (Debian/Ubuntu:"
                    + " libaio1 or libaio1t64, libncurses6).";
        }
        return "";
    }

    private RedactingFilter redactor() {
        return RedactingFilter.of(List.of(secrets.root(), secrets.application()));
    }

    /** The last lines of the log file, without control characters and passwords. */
    private String logTail() {
        Path log = layout.logFile();
        if (!Files.isRegularFile(log)) {
            return "";
        }
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            long length = file.length();
            int size = (int) Math.min(length, 16 * 1024);
            byte[] bytes = new byte[size];
            file.seek(length - size);
            file.readFully(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>(List.of(text.split("\\R")));
            List<String> tail = lines.subList(Math.max(0, lines.size() - LOG_TAIL_LINES), lines.size());
            StringBuilder out = new StringBuilder("\nLast lines of ").append(log).append(':');
            RedactingFilter redact = redactor();
            for (String line : tail) {
                StringBuilder clean = new StringBuilder(line.length());
                for (int i = 0; i < line.length(); i++) {
                    char c = line.charAt(i);
                    clean.append(Character.isISOControl(c) ? '?' : c);
                }
                out.append("\n  ").append(redact.redact(clean.toString()));
            }
            return out.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean hasContent(Path dir) throws DatabaseException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            return entries.iterator().hasNext();
        } catch (IOException e) {
            throw new DatabaseException(Reason.INITIALIZE, "Reading " + dir + " failed: " + e.getMessage(), e);
        }
    }

    /** Creates the file readable by the owner only, then writes it. Fails if it already exists. */
    static void writeOwnerOnly(Path file, String content) throws IOException {
        boolean posix = Files.getFileStore(file.getParent()).supportsFileAttributeView("posix");
        FileAttribute<?>[] attributes = posix
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
        Files.createFile(file, attributes);
        OwnerOnlyPermissions.restrictFile(file);
        Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
    }
}
