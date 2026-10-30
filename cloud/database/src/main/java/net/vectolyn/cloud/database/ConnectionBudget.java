package net.vectolyn.cloud.database;

/**
 * Central sizing of the connection pools. Every server with LuckPerms (later the Vectolyn plugin)
 * keeps a pool open, so the sum of all pools must stay below the server's {@code max_connections}.
 * That was the cause of the old "Too many connections" errors.
 *
 * <ul>
 *   <li>Each service gets {@value #DEFAULT_POOL_SIZE} connections, the pool size the templates used
 *       so far, as long as that fits.</li>
 *   <li>When more services run, every pool shrinks evenly, but never below
 *       {@value #MIN_POOL_SIZE} ({@code minimum-idle} in the templates).</li>
 *   <li>{@code reserved} connections stay free for the cloud and for administration.</li>
 * </ul>
 *
 * @param maxConnections {@code max_connections} of the server
 * @param reserved connections kept free
 */
public record ConnectionBudget(int maxConnections, int reserved) {

    /** {@code max_connections} of the managed server, as in the old {@code vectolyn-mariadb.ini}. */
    public static final int MANAGED_MAX_CONNECTIONS = 150;
    /** Connections kept free for the cloud and administration. */
    public static final int DEFAULT_RESERVED = 20;
    /** Pool size per service while the budget allows it. */
    public static final int DEFAULT_POOL_SIZE = 4;
    /** Smallest pool; equals {@code minimum-idle} in the templates. */
    public static final int MIN_POOL_SIZE = 2;

    /**
     * Checks the values.
     *
     * @param maxConnections {@code max_connections}
     * @param reserved connections kept free
     */
    public ConnectionBudget {
        if (reserved < 0 || maxConnections - reserved < MIN_POOL_SIZE) {
            throw new IllegalArgumentException("connection budget leaves no room for a single pool");
        }
    }

    /**
     * The budget of the managed server: 150 connections, 20 of them kept free.
     *
     * @return the budget
     */
    public static ConnectionBudget managed() {
        return new ConnectionBudget(MANAGED_MAX_CONNECTIONS, DEFAULT_RESERVED);
    }

    /**
     * The budget of a server whose {@code max_connections} was read from it (external mode):
     * {@value #DEFAULT_RESERVED} connections are kept free, or a fifth of them on a server with
     * fewer than 100.
     *
     * @param maxConnections {@code @@max_connections} of the server
     * @return the budget
     * @throws DatabaseException if the server allows too few connections for a single pool
     */
    public static ConnectionBudget forServer(int maxConnections) throws DatabaseException {
        int reserved = Math.min(DEFAULT_RESERVED, Math.max(0, maxConnections) / 5);
        if (maxConnections - reserved < MIN_POOL_SIZE) {
            throw new DatabaseException(DatabaseException.Reason.CONNECTION_BUDGET, "The database server allows "
                    + maxConnections + " connections (max_connections), too few for the connection pools of the"
                    + " servers. Raise max_connections on the server.");
        }
        return new ConnectionBudget(maxConnections, reserved);
    }

    /**
     * Returns how many connections the pools may use together.
     *
     * @return {@code maxConnections - reserved}
     */
    public int available() {
        return maxConnections - reserved;
    }

    /**
     * Returns the pool size for each of {@code services} services.
     *
     * @param services number of services with a database pool that may run at the same time
     * @return the pool size, between {@value #MIN_POOL_SIZE} and {@value #DEFAULT_POOL_SIZE}
     * @throws DatabaseException if even the smallest pools do not fit
     */
    public int poolSizeFor(int services) throws DatabaseException {
        if (services < 0) {
            throw new IllegalArgumentException("services must not be negative");
        }
        if (services == 0) {
            return DEFAULT_POOL_SIZE;
        }
        int size = Math.min(DEFAULT_POOL_SIZE, available() / services);
        if (size < MIN_POOL_SIZE) {
            throw new DatabaseException(DatabaseException.Reason.CONNECTION_BUDGET, services
                    + " services with a database pool need at least " + (services * MIN_POOL_SIZE)
                    + " connections, but the server allows " + available() + " for pools (max_connections "
                    + maxConnections + ", " + reserved + " kept free). Lower the maximum number of services"
                    + " or raise max_connections.");
        }
        return size;
    }
}
