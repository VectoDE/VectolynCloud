package net.vectolyn.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** How the cloud gets its MariaDB server. */
public enum DatabaseMode {
    /** The cloud downloads MariaDB and runs it itself (Windows x64 and Linux x86_64 with glibc). */
    @JsonProperty("managed") MANAGED,
    /** The cloud starts MariaDB as a Docker container. */
    @JsonProperty("docker") DOCKER,
    /** An existing MariaDB or MySQL server that the operator runs. */
    @JsonProperty("external") EXTERNAL,
    /** Embedded H2 database, for tests only. */
    @JsonProperty("h2") H2
}
