package net.vectora.cloud.api.auth;

import java.util.Locale;
import java.util.Optional;

/**
 * What a token may do. A token has one or more scopes; {@link #ADMIN} includes every other one.
 * Read scopes do not include the write scope of the same area, and the write scopes do not include
 * the read scopes either, so a script that only restarts servers can be given exactly that.
 */
public enum Scope {
    /** List and inspect services, receive live events ({@code /api/v1/events}). */
    SERVICES_READ("services:read"),
    /** Start, stop and restart services, send console commands. */
    SERVICES_WRITE("services:write"),
    /** Read {@code cloud.yml}, groups, tasks, wanted switches and the setup status. */
    CONFIG_READ("config:read"),
    /** Change and reload the configuration. */
    CONFIG_WRITE("config:write"),
    /** List backups. */
    BACKUPS_READ("backups:read"),
    /** Make backups. */
    BACKUPS_WRITE("backups:write"),
    /** Read the node status. */
    NODE_READ("node:read"),
    /** Everything above. */
    ADMIN("admin");

    private final String id;

    Scope(String id) {
        this.id = id;
    }

    /**
     * The name used in the console, the token file and the API, for example {@code services:read}.
     *
     * @return the name
     */
    public String id() {
        return id;
    }

    /**
     * Finds a scope by its name, ignoring case.
     *
     * @param id for example {@code config:write}
     * @return the scope, or empty if there is none with this name
     */
    public static Optional<Scope> of(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        for (Scope scope : values()) {
            if (scope.id.equals(wanted)) {
                return Optional.of(scope);
            }
        }
        return Optional.empty();
    }
}
