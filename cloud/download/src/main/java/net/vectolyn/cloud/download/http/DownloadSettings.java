package net.vectolyn.cloud.download.http;

import java.time.Duration;
import java.util.Objects;

/**
 * Timeouts, retries and the User-Agent of {@link DownloadClient}.
 *
 * @param userAgent the User-Agent header, see {@link #userAgent(String)}
 * @param requestTimeout time until the response headers must have arrived
 * @param transferTimeout time one attempt may take in total, body included
 * @param maxAttempts attempts per request, at least 1
 * @param initialBackoff wait before the second attempt; doubles for each further one
 * @param maxBackoff upper bound of the wait between attempts
 */
public record DownloadSettings(
        String userAgent,
        Duration requestTimeout,
        Duration transferTimeout,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff) {

    /** Project URL in the User-Agent, so API operators can see who is calling. */
    public static final String PROJECT_URL = "https://github.com/VectoDE/VectolynCloud";

    /** Time allowed for the TCP and TLS handshake in {@link #defaults()}. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    /**
     * Checks the values.
     *
     * @param userAgent the User-Agent header
     * @param requestTimeout time until the response headers must have arrived
     * @param transferTimeout time one attempt may take in total
     * @param maxAttempts attempts per request
     * @param initialBackoff first wait between attempts
     * @param maxBackoff longest wait between attempts
     */
    public DownloadSettings {
        Objects.requireNonNull(userAgent, "userAgent");
        if (userAgent.isBlank() || userAgent.chars().anyMatch(c -> c < 0x20 || c > 0x7e)) {
            throw new IllegalArgumentException("User-Agent must be printable ASCII");
        }
        requirePositive(requestTimeout, "requestTimeout");
        requirePositive(transferTimeout, "transferTimeout");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        if (initialBackoff.isNegative() || maxBackoff.isNegative()) {
            throw new IllegalArgumentException("backoff must not be negative");
        }
    }

    /**
     * Returns the defaults: 30 s until the headers, 15 min per transfer, 3 attempts, backoff
     * starting at 1 s and capped at 10 s, User-Agent with the version of this module.
     *
     * @return the default settings
     */
    public static DownloadSettings defaults() {
        String version = DownloadSettings.class.getPackage().getImplementationVersion();
        return new DownloadSettings(userAgent(version == null ? "dev" : version),
                Duration.ofSeconds(30), Duration.ofMinutes(15), 3, Duration.ofSeconds(1), Duration.ofSeconds(10));
    }

    /**
     * Builds the User-Agent {@code VectolynCloud/<version> (+https://github.com/VectoDE/VectolynCloud)}.
     * PaperMC and Modrinth ask for a User-Agent that names the application and a contact.
     *
     * @param version the VectolynCloud version
     * @return the header value
     */
    public static String userAgent(String version) {
        return "VectolynCloud/" + version + " (+" + PROJECT_URL + ")";
    }

    /**
     * Returns a copy with another number of attempts.
     *
     * @param attempts attempts per request
     * @return the new settings
     */
    public DownloadSettings withMaxAttempts(int attempts) {
        return new DownloadSettings(userAgent, requestTimeout, transferTimeout, attempts, initialBackoff, maxBackoff);
    }

    /**
     * Returns the wait before the given attempt.
     *
     * @param attempt the attempt that is about to start, 2 or higher
     * @return the wait
     */
    public Duration backoffBefore(int attempt) {
        Duration delay = initialBackoff;
        for (int i = 2; i < attempt && delay.compareTo(maxBackoff) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
