package net.vectolyn.cloud.api.auth;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * What the cloud knows about a token: never the token itself, only its id, name and scopes. The
 * SHA-256 of the token stays inside {@link ApiTokens}.
 *
 * @param id        short public id for {@code api token revoke}, 8 hex characters
 * @param name      label chosen by the operator, {@code [a-z0-9][a-z0-9_-]{0,31}}
 * @param scopes    what the token may do, never empty
 * @param createdAt when it was created
 */
public record ApiToken(String id, String name, Set<Scope> scopes, Instant createdAt) {

    /** Checks and copies the values. */
    public ApiToken {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdAt, "createdAt");
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("a token needs at least one scope");
        }
        scopes = Set.copyOf(EnumSet.copyOf(scopes));
    }

    /**
     * Whether the token may do what {@code scope} covers; {@link Scope#ADMIN} may do everything.
     *
     * @param scope the required scope
     * @return whether it is granted
     */
    public boolean allows(Scope scope) {
        return scopes.contains(Scope.ADMIN) || scopes.contains(scope);
    }
}
