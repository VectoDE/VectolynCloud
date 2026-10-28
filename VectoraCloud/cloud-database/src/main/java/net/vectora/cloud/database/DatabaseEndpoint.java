package net.vectora.cloud.database;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.vectora.cloud.database.sql.ServerVersion;

/**
 * Where the servers find their database once a {@link DatabaseProvisioner} is done. It holds no
 * password; {@code VECTORA_DB_PASSWORD} comes from {@code secrets/vectora.env}.
 *
 * @param storageMethod what the servers write to
 * @param host host the servers connect to
 * @param port port the servers connect to
 * @param database database name
 * @param user user name
 * @param version server version, empty for H2 or when unknown
 */
public record DatabaseEndpoint(StorageMethod storageMethod, String host, int port, String database, String user,
        Optional<ServerVersion> version) {

    /** Placeholder for the pool size of one service. */
    public static final String POOL_SIZE = "VECTORA_DB_POOL_SIZE";
    /** Placeholder for the storage method. */
    public static final String STORAGE_METHOD = "VECTORA_DB_STORAGE_METHOD";

    /**
     * Checks the values.
     *
     * @param storageMethod the storage method
     * @param host the host
     * @param port the port
     * @param database the database name
     * @param user the user
     * @param version the version
     */
    public DatabaseEndpoint {
        Objects.requireNonNull(storageMethod, "storageMethod");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(version, "version");
    }

    /**
     * Returns the non-secret {@code VECTORA_DB_*} placeholder values for a service. They satisfy
     * the template rules: no control characters, {@code VECTORA_DB_PORT} is a port.
     *
     * @param poolSize the pool size from {@link ConnectionBudget#poolSizeFor}
     * @return {@code VECTORA_DB_HOST}, {@code _PORT}, {@code _NAME}, {@code _USER},
     *     {@code _STORAGE_METHOD} and {@code _POOL_SIZE}
     */
    public Map<String, String> placeholderValues(int poolSize) {
        if (poolSize < ConnectionBudget.MIN_POOL_SIZE) {
            throw new IllegalArgumentException("pool size must be at least " + ConnectionBudget.MIN_POOL_SIZE);
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("VECTORA_DB_HOST", host);
        values.put("VECTORA_DB_PORT", Integer.toString(port));
        values.put("VECTORA_DB_NAME", database);
        values.put("VECTORA_DB_USER", user);
        values.put(STORAGE_METHOD, storageMethod.placeholderValue());
        values.put(POOL_SIZE, Integer.toString(poolSize));
        return Map.copyOf(values);
    }
}
