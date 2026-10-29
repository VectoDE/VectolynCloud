package net.vectora.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Kind of a catalog entry a group installs. */
public enum CatalogKind {
    /** A plugin for Paper, PandaSpigot or Velocity. */
    @JsonProperty("plugin") PLUGIN,
    /** A Fabric mod. */
    @JsonProperty("mod") MOD,
    /**
     * A third-party plugin used only until Vectora's own replacement is ready (LuckPerms, TAB,
     * PlaceholderAPI, WorldEdit, WorldGuard, Chunky, spark, ProxyPortal). Each one is removed
     * once its replacement ships.
     */
    @JsonProperty("transitional") TRANSITIONAL
}
