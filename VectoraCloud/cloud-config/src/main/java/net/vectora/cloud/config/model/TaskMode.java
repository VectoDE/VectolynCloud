package net.vectora.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Whether one service keeps its folder or is created on demand. Decided per service when it is
 * created, from {@link ScalingConfig#staticServices()} of its task.
 */
public enum TaskMode {
    /** The service keeps its data between starts (Survival, Creative, the first lobby, ...). */
    @JsonProperty("static") STATIC,
    /** The service is created from the template on each start and deleted after the stop. */
    @JsonProperty("dynamic") DYNAMIC
}
