package net.vectora.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * What the node does when a service of the group crashes (its process ends without a stop
 * request and with an exit code other than 0).
 *
 * @param onCrash     whether a crashed service is started again
 * @param maxAttempts restarts in a row before the node gives up, 1 to {@link #MAX_ATTEMPTS}; the
 *                    count starts again once a service has run for {@link #STABLE_AFTER_SECONDS}
 * @param backoff     wait before the first restart; doubled for every further attempt
 * @param maxBackoff  upper limit of the wait, at least {@code backoff}
 *
 * <p>All four fields are required once the {@code restart} section is present, so a forgotten
 * {@code on-crash} cannot silently switch restarts off.
 */
@JsonPropertyOrder({"on-crash", "max-attempts", "backoff", "max-backoff"})
public record RestartConfig(@JsonProperty(required = true) boolean onCrash,
                            @JsonProperty(required = true) int maxAttempts,
                            @JsonProperty(required = true) TimeSpan backoff,
                            @JsonProperty(required = true) TimeSpan maxBackoff) {

    /** Upper limit of {@link #maxAttempts()}. */
    public static final int MAX_ATTEMPTS = 1000;
    /** Uptime after which a service counts as stable and its attempt count is reset. */
    public static final long STABLE_AFTER_SECONDS = 300;

    /** Restart on crash, up to 5 times, waiting 5 s, 10 s, 20 s ... and at most 5 minutes. */
    public static RestartConfig defaults() {
        return new RestartConfig(true, 5, TimeSpan.ofSeconds(5), TimeSpan.ofSeconds(300));
    }
}
