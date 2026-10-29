package net.vectora.cloud.backup.database;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.secret.EnvFile;
import net.vectora.cloud.config.secret.GeneratedSecret;
import net.vectora.cloud.config.secret.Secret;

/**
 * How to reach the database for a dump.
 *
 * <ul>
 *   <li>{@code managed}: as {@code root} over TCP (the server only listens on loopback), with
 *       {@code VECTORA_DB_ROOT_PASSWORD}, so routines, events and triggers are dumped too. The dump
 *       tool comes from the installed server ({@code managedBase/bin}).</li>
 *   <li>{@code external}: as the application user with {@code VECTORA_DB_PASSWORD}; the cloud never
 *       uses root on a server it does not own.</li>
 *   <li>{@code docker} and {@code h2}: no credentials; the first gets a hint, the second is dumped
 *       per service.</li>
 * </ul>
 *
 * @param mode the database mode
 * @param host host to connect to
 * @param port port
 * @param database database name
 * @param user user for the dump; empty for docker and h2
 * @param password password; empty for docker and h2. Its {@code toString} is masked.
 * @param managedBase folder of the installed MariaDB server (managed mode), or null
 */
public record DatabaseAccess(DatabaseMode mode, String host, int port, String database, String user, Secret password,
                             Path managedBase) {

    /** User for the managed mode. */
    public static final String ROOT = "root";

    /** Checks the values. */
    public DatabaseAccess {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(database, "database");
        user = user == null ? "" : user;
        password = password == null ? Secret.of("") : password;
    }

    /**
     * Builds the access data from {@code cloud.yml} and {@code secrets/vectora.env}.
     *
     * @param config the {@code database} section of {@code cloud.yml}
     * @param secrets the secrets file
     * @param managedBase the installed MariaDB server, or null when unknown or not managed
     * @return the access data
     */
    public static DatabaseAccess of(DatabaseConfig config, EnvFile secrets, Path managedBase) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(secrets, "secrets");
        return switch (config.mode()) {
            case MANAGED -> new DatabaseAccess(DatabaseMode.MANAGED, config.host(), config.port(), config.name(), ROOT,
                    secrets.get(GeneratedSecret.DB_ROOT_PASSWORD.key()).orElse(Secret.of("")), managedBase);
            case EXTERNAL -> new DatabaseAccess(DatabaseMode.EXTERNAL, config.host(), config.port(), config.name(),
                    config.user(), secrets.get(GeneratedSecret.DB_PASSWORD.key()).orElse(Secret.of("")), null);
            case DOCKER, H2 -> new DatabaseAccess(config.mode(), config.host(), config.port(), config.name(), "", null,
                    null);
        };
    }

    /** The managed server folder, if known. */
    public Optional<Path> managedBaseFolder() {
        return Optional.ofNullable(managedBase);
    }
}
