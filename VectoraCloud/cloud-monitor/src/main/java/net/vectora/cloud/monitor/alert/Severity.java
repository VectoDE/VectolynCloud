package net.vectora.cloud.monitor.alert;

import com.fasterxml.jackson.annotation.JsonProperty;

/** How urgent an alert is. */
public enum Severity {
    /** Needs attention soon. */
    @JsonProperty("warning") WARNING,
    /** Needs attention now. */
    @JsonProperty("critical") CRITICAL;

    /** Lower-case name, as in {@code monitor.yml}. */
    public String id() {
        return this == WARNING ? "warning" : "critical";
    }
}
