package net.vectolyn.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Server software a group runs. */
public enum ServerSoftware {
    /** Paper (backends). */
    @JsonProperty("paper") PAPER,
    /** Velocity (proxies). */
    @JsonProperty("velocity") VELOCITY,
    /** Fabric with Fabric API (Mod-Server). */
    @JsonProperty("fabric") FABRIC,
    /** PandaSpigot, the Java 21 capable 1.8.8 fork behind the legacy proxy. */
    @JsonProperty("pandaspigot") PANDASPIGOT
}
