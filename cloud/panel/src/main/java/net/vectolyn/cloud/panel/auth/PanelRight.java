// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import net.vectolyn.cloud.api.auth.Scope;

/**
 * What a panel user may do. The first ten are the scopes of the REST API ({@link Scope}); the
 * panel passes them on when it calls the API for the user. The others guard the panel's own pages.
 */
public enum PanelRight {

    /** Read services, groups and their state. */
    SERVICES_READ("services:read", Scope.SERVICES_READ),
    /** Start, stop and change services; send console commands. */
    SERVICES_WRITE("services:write", Scope.SERVICES_WRITE),
    /** Read the configuration. */
    CONFIG_READ("config:read", Scope.CONFIG_READ),
    /** Change the configuration. */
    CONFIG_WRITE("config:write", Scope.CONFIG_WRITE),
    /** List backups. */
    BACKUPS_READ("backups:read", Scope.BACKUPS_READ),
    /** Create and restore backups. */
    BACKUPS_WRITE("backups:write", Scope.BACKUPS_WRITE),
    /** Read node and cluster state. */
    NODE_READ("node:read", Scope.NODE_READ),
    /** Read template files. */
    TEMPLATES_READ("templates:read", Scope.TEMPLATES_READ),
    /** Change template files. */
    TEMPLATES_WRITE("templates:write", Scope.TEMPLATES_WRITE),
    /** Everything the API allows, including tokens. */
    ADMIN("admin", Scope.ADMIN),
    /** Read files of services and templates. */
    FILES_READ("files:read", null),
    /** Change files of services and templates. */
    FILES_WRITE("files:write", null),
    /** Kick and ban players. */
    PLAYERS_MANAGE("players:manage", null),
    /** Read the panel's audit log. */
    AUDIT_READ("audit:read", null),
    /** Manage panel users and roles. */
    USERS_MANAGE("users:manage", null);

    private final String id;
    private final Scope scope;

    PanelRight(String id, Scope scope) {
        this.id = id;
        this.scope = scope;
    }

    /**
     * The id used in files and JSON.
     *
     * @return for example {@code services:read}
     */
    @JsonValue
    public String id() {
        return id;
    }

    /**
     * The API scope this right grants.
     *
     * @return the scope, or empty for a right of the panel only
     */
    public Optional<Scope> scope() {
        return Optional.ofNullable(scope);
    }

    /**
     * Looks a right up by id.
     *
     * @param id the id
     * @return the right, or empty
     */
    public static Optional<PanelRight> of(String id) {
        return Arrays.stream(values()).filter(r -> r.id.equals(id)).findFirst();
    }

    /**
     * Reads a right from a file or a request.
     *
     * @param id the id
     * @return the right
     * @throws IllegalArgumentException for an unknown id
     */
    @JsonCreator
    public static PanelRight fromId(String id) {
        return of(id).orElseThrow(() -> new IllegalArgumentException("unknown right"));
    }

    /**
     * The API scopes of a set of rights.
     *
     * @param rights the rights
     * @return the scopes, possibly empty
     */
    public static Set<Scope> scopes(Set<PanelRight> rights) {
        Set<Scope> scopes = EnumSet.noneOf(Scope.class);
        for (PanelRight right : rights) {
            right.scope().ifPresent(scopes::add);
        }
        return scopes;
    }
}
