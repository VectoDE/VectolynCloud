package net.vectora.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Whether a task keeps its services or creates them on demand. */
public enum TaskMode {
    /** Services keep their data between starts (Survival, Creative, ...). */
    @JsonProperty("static") STATIC,
    /** Services are created from the template on each start and scaled by player count. */
    @JsonProperty("dynamic") DYNAMIC
}
