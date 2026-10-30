package net.vectolyn.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Combat mechanics a group runs with, so each game mode can choose its own. */
public enum CombatSystem {
    /** 1.8-style combat without attack cooldown. */
    @JsonProperty("legacy") LEGACY,
    /** Combat of the server's Minecraft version (attack cooldown since 1.9). */
    @JsonProperty("modern") MODERN
}
