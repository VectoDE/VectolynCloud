package net.vectora.cloud.database;

import java.time.Duration;
import java.util.Objects;

/**
 * Result of {@link DatabaseProvisioner#health()}.
 *
 * @param healthy whether the database answered
 * @param latency time the check took
 * @param detail short text for the console: the server version, or why the check failed
 */
public record HealthReport(boolean healthy, Duration latency, String detail) {

    /**
     * Checks the values.
     *
     * @param healthy whether the database answered
     * @param latency time the check took
     * @param detail short text
     */
    public HealthReport {
        Objects.requireNonNull(latency, "latency");
        Objects.requireNonNull(detail, "detail");
    }
}
