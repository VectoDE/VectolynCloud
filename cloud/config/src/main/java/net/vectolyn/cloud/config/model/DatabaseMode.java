// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** How the cloud gets its database server. */
public enum DatabaseMode {
    /** The cloud downloads MariaDB and runs it itself (Windows x64 and Linux x86_64 with glibc). */
    @JsonProperty("managed") MANAGED,
    /** The cloud starts MariaDB as a Docker container. */
    @JsonProperty("docker") DOCKER,
    /** An existing MariaDB or MySQL server that the operator runs. */
    @JsonProperty("external") EXTERNAL,
    /** Embedded H2 database, for tests only. */
    @JsonProperty("h2") H2,
    /** An existing PostgreSQL server that the operator runs. */
    @JsonProperty("postgresql") POSTGRESQL;

    /**
     * Whether the operator runs the server, so the cloud only connects to it: {@link #EXTERNAL}
     * and {@link #POSTGRESQL}. Such a server may be reachable from other machines.
     *
     * @return whether the server is the operator's
     */
    public boolean operatorServer() {
        return this == EXTERNAL || this == POSTGRESQL;
    }
}
