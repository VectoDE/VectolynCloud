// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api.auth;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What the cloud knows about a token: never the token itself, only its id, name and scopes. The
 * SHA-256 of the token stays inside {@link ApiTokens}.
 *
 * @param id        short public id for {@code api token revoke}, 8 hex characters
 * @param name      label chosen by the operator, {@code [a-z0-9][a-z0-9_-]{0,31}}
 * @param scopes    what the token may do, never empty
 * @param createdAt when it was created
 * @param expiresAt when it stops working, or null if it works until it is revoked
 */
public record ApiToken(String id, String name, Set<Scope> scopes, Instant createdAt,
        @Nullable Instant expiresAt) {

    /** Checks and copies the values. */
    public ApiToken {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdAt, "createdAt");
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("a token needs at least one scope");
        }
        scopes = Set.copyOf(EnumSet.copyOf(scopes));
        if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("a token must expire after it was created");
        }
    }

    /**
     * A token without expiry.
     *
     * @param id        short public id
     * @param name      label
     * @param scopes    what it may do
     * @param createdAt when it was created
     */
    public ApiToken(String id, String name, Set<Scope> scopes, Instant createdAt) {
        this(id, name, scopes, createdAt, null);
    }

    /**
     * Whether the token has expired at {@code now}.
     *
     * @param now the time to check
     * @return true from {@link #expiresAt()} on
     */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
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
