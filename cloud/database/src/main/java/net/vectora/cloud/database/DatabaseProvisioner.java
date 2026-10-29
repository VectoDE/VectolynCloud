package net.vectora.cloud.database;

import net.vectora.cloud.config.model.DatabaseMode;

/**
 * Makes the network's database usable, in one of the four modes of {@code database.mode}.
 *
 * <table>
 *   <caption>Modes</caption>
 *   <tr><th>Mode</th><th>{@link #provision()}</th><th>{@link #shutdown()}</th></tr>
 *   <tr><td>{@code managed}</td><td>downloads MariaDB, creates the data directory, starts the server
 *       through {@link DatabaseProcesses}, secures it, creates database and user</td>
 *       <td>stops the server cleanly</td></tr>
 *   <tr><td>{@code docker}</td><td>writes a {@code compose} file and a start hint; once the
 *       container answers, secures it and creates database and user. Never talks to the Docker
 *       socket.</td><td>nothing</td></tr>
 *   <tr><td>{@code external}</td><td>checks that the application user can log in and use its
 *       database</td><td>nothing</td></tr>
 *   <tr><td>{@code h2}</td><td>nothing; the servers keep embedded H2 files</td><td>nothing</td></tr>
 * </table>
 *
 * <p>The caller is {@code cloud-node}. It calls {@link #provision()} before it starts the first
 * service that needs the database, and {@link #shutdown()} after the last one stopped.
 */
public interface DatabaseProvisioner {

    /**
     * Returns the mode this provisioner implements.
     *
     * @return the mode
     */
    DatabaseMode mode();

    /**
     * Brings the database into the wanted state. Safe to call on every start: every step checks
     * first and only does what is missing.
     *
     * @return where the servers find the database
     * @throws DatabaseException if the database cannot be prepared or reached; the message says why
     *     and what to do
     * @throws InterruptedException if interrupted while waiting
     */
    DatabaseEndpoint provision() throws DatabaseException, InterruptedException;

    /**
     * Checks whether the database answers.
     *
     * @return the result; never throws for an unreachable database
     */
    HealthReport health();

    /**
     * Returns a note for the operator that {@code cloud-node} logs as a warning after
     * {@link #provision()}, for example that the embedded fallback replaced the configured mode.
     *
     * @return the note, or empty
     */
    default java.util.Optional<String> notice() {
        return java.util.Optional.empty();
    }

    /**
     * Returns how many connections the pools of all services may use together. Valid after
     * {@link #provision()}. The cloud's own servers (managed, Docker) allow
     * {@value ConnectionBudget#MANAGED_MAX_CONNECTIONS}; an external server is asked for its
     * {@code max_connections}.
     *
     * @return the budget
     */
    default ConnectionBudget connectionBudget() {
        return ConnectionBudget.managed();
    }

    /**
     * Stops what this provisioner started. Does nothing for modes that start nothing.
     *
     * @throws InterruptedException if interrupted while waiting for the server to stop
     */
    void shutdown() throws InterruptedException;
}
