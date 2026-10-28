package net.vectora.cloud.database;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.database.docker.DockerProvisioner;
import net.vectora.cloud.database.managed.ManagedMariaDbProvisioner;
import net.vectora.cloud.database.managed.ManagedPlatform;
import net.vectora.cloud.database.managed.MariaDbBinaries;
import net.vectora.cloud.database.managed.MariaDbLayout;
import net.vectora.cloud.database.sql.SqlConnector;
import net.vectora.cloud.download.provider.TargetPlatform;

/** Picks the {@link DatabaseProvisioner} for {@code database.mode}. */
public final class DatabaseProvisioners {

    /** How long the Docker mode waits for the container before it shows the start command. */
    public static final Duration DOCKER_WAIT = Duration.ofSeconds(20);

    private DatabaseProvisioners() {
    }

    /**
     * What the managed mode needs besides the settings. Unused by the other modes.
     *
     * @param binaries supplies the unpacked server
     * @param processes runs the programs (implemented by {@code cloud-node})
     * @param system the platform the cloud runs on
     */
    public record Managed(MariaDbBinaries binaries, DatabaseProcesses processes, TargetPlatform system) {

        /**
         * Checks the values.
         *
         * @param binaries the binaries
         * @param processes the processes
         * @param system the system
         */
        public Managed {
            Objects.requireNonNull(binaries, "binaries");
            Objects.requireNonNull(processes, "processes");
            Objects.requireNonNull(system, "system");
        }
    }

    /**
     * Creates the provisioner for the configured mode. Mode {@code managed} on a system without a
     * MariaDB server build (see {@link ManagedPlatform}) gets the embedded H2 fallback instead of an
     * error; its {@link DatabaseProvisioner#notice()} says so.
     *
     * @param config database settings
     * @param secrets the passwords from {@code secrets/vectora.env}
     * @param home the database home, {@code Server-Network/Database/runtime}
     * @param connector opens connections (normally {@link net.vectora.cloud.database.sql.JdbcConnector})
     * @param managed what the managed mode needs
     * @return the provisioner
     */
    public static DatabaseProvisioner create(DatabaseConfig config, DatabaseSecrets secrets, Path home,
            SqlConnector connector, Managed managed) {
        Objects.requireNonNull(config, "config");
        return switch (config.mode()) {
            case MANAGED -> ManagedPlatform.find(managed.system()).isPresent()
                    ? new ManagedMariaDbProvisioner(config, secrets, new MariaDbLayout(home),
                            managed.binaries(), managed.processes(), connector, managed.system(),
                            ManagedMariaDbProvisioner.Timeouts.defaults())
                    : EmbeddedProvisioner.fallback(config, "MariaDB publishes server builds for Windows x64 and"
                            + " Linux x86_64 with glibc only, and this system is " + managed.system() + ".");
            case DOCKER -> new DockerProvisioner(config, secrets, home, connector, DOCKER_WAIT);
            case EXTERNAL -> new ExternalProvisioner(config, secrets.application(), connector);
            case H2 -> new EmbeddedProvisioner(config);
        };
    }
}
