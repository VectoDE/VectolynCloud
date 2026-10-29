package net.vectora.cloud.backup;

import java.io.IOException;
import java.util.Optional;
import net.vectora.cloud.backup.database.DatabaseAccess;

/**
 * Where the database of the network is. cloud-node implements it with
 * {@link DatabaseAccess#of(net.vectora.cloud.config.model.DatabaseConfig, net.vectora.cloud.config.secret.EnvFile, java.nio.file.Path)}
 * from {@code cloud.yml}, {@code secrets/vectora.env} and the folder the managed MariaDB was
 * installed to.
 */
@FunctionalInterface
public interface DatabaseSource {

    /** No database at all. */
    DatabaseSource NONE = Optional::empty;

    /**
     * The database to back up.
     *
     * @return the access data, or empty for none
     * @throws IOException if the secrets cannot be read
     */
    Optional<DatabaseAccess> access() throws IOException;
}
