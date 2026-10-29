package net.vectora.cloud.database.docker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.secret.RedactingFilter;
import net.vectora.cloud.database.DatabaseEndpoint;
import net.vectora.cloud.database.DatabaseException;
import net.vectora.cloud.database.DatabaseException.Reason;
import net.vectora.cloud.database.DatabaseProvisioner;
import net.vectora.cloud.database.DatabaseSecrets;
import net.vectora.cloud.database.HealthReport;
import net.vectora.cloud.database.ServerSetup;
import net.vectora.cloud.database.StorageMethod;
import net.vectora.cloud.database.sql.ConnectionTarget;
import net.vectora.cloud.database.sql.ServerVersion;
import net.vectora.cloud.database.sql.SqlConnector;
import net.vectora.cloud.database.sql.SqlSession;
import net.vectora.cloud.database.sql.TlsMode;

/**
 * Mode {@code docker}: MariaDB in a container the operator starts with {@code docker compose}.
 *
 * <p>{@link #provision()} writes {@link DockerComposeFile} and then waits briefly for the container.
 * If it does not answer, the exception carries the start command. Once it answers, the cloud
 * secures it and creates database and user, as in the managed mode. The application user's host
 * part is {@code %}: connections through Docker's port forwarding arrive from the bridge network,
 * not from 127.0.0.1. The published port is bound to 127.0.0.1, so nothing outside this machine
 * reaches it.
 *
 * <p>Not available on Pterodactyl: a Pterodactyl server is itself a container without Docker inside.
 */
public final class DockerProvisioner implements DatabaseProvisioner {

    /** Host part of the application user in the container. */
    public static final String APPLICATION_HOST = "%";
    /** Host parts of {@code root} that stay; the image creates {@code root@%}. */
    public static final Set<String> ROOT_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "%");

    private final DatabaseConfig config;
    private final DatabaseSecrets secrets;
    private final Path home;
    private final SqlConnector connector;
    private final Duration wait;
    private final ConnectionTarget target;

    /**
     * Creates the provisioner.
     *
     * @param config database settings, mode {@code docker}
     * @param secrets root and application password
     * @param home folder for the compose file, {@code Server-Network/Database/runtime}
     * @param connector the connector
     * @param wait how long to wait for the container before giving the start hint
     */
    public DockerProvisioner(DatabaseConfig config, DatabaseSecrets secrets, Path home, SqlConnector connector,
            Duration wait) {
        this.config = Objects.requireNonNull(config, "config");
        if (config.mode() != DatabaseMode.DOCKER) {
            throw new IllegalArgumentException("database mode docker expected, got " + config.mode());
        }
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.home = Objects.requireNonNull(home, "home");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.wait = Objects.requireNonNull(wait, "wait");
        this.target = new ConnectionTarget("127.0.0.1", config.port(), TlsMode.DISABLE, ConnectionTarget.DEFAULT_TIMEOUT);
    }

    @Override
    public DatabaseMode mode() {
        return DatabaseMode.DOCKER;
    }

    /**
     * Returns where the compose file is written.
     *
     * @return the file
     */
    public Path composeFile() {
        return home.resolve(DockerComposeFile.FILE_NAME);
    }

    @Override
    public DatabaseEndpoint provision() throws DatabaseException, InterruptedException {
        if (secrets.root().isEmpty() || secrets.application().isEmpty()) {
            throw new DatabaseException(Reason.SETTINGS, "VECTORA_DB_ROOT_PASSWORD and VECTORA_DB_PASSWORD in"
                    + " secrets/vectora.env must not be empty.");
        }
        try {
            Files.createDirectories(home);
            Files.writeString(composeFile(), DockerComposeFile.render(config), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DatabaseException(Reason.INSTALL, "Writing " + composeFile() + " failed: " + e.getMessage(), e);
        }
        Optional<ServerVersion> version;
        try (SqlSession root = ServerSetup.openWithRetry(connector, target, "root", secrets.root(), wait, () -> true)) {
            ServerSetup.secureAndCreate(root, config, APPLICATION_HOST, ROOT_HOSTS, secrets);
            version = ServerVersion.parse(root.serverVersion());
        } catch (SQLException e) {
            throw new DatabaseException(Reason.UNREACHABLE, "The MariaDB container does not answer on " + target
                    + " (" + ServerSetup.describe(e, RedactingFilter.of(List.of(secrets.root()))) + "). Start it in "
                    + home + " with: " + DockerComposeFile.startCommand(), e);
        }
        DatabaseConfig local = new DatabaseConfig(DatabaseMode.DOCKER, "127.0.0.1", config.port(), config.name(),
                config.user(), config.dockerImage());
        ServerSetup.checkApplicationLogin(connector, target, local, secrets.application());
        return new DatabaseEndpoint(StorageMethod.MARIADB, "127.0.0.1", config.port(), config.name(), config.user(),
                version);
    }

    @Override
    public HealthReport health() {
        return ServerSetup.health(connector, target, config.user(), secrets.application());
    }

    @Override
    public void shutdown() {
        // The operator runs the container.
    }
}
