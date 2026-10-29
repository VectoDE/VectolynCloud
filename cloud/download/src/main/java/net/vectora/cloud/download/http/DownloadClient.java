package net.vectora.cloud.download.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.vectora.cloud.download.DownloadException;
import net.vectora.cloud.download.HostNotAllowedException;
import net.vectora.cloud.download.HttpStatusException;
import net.vectora.cloud.download.SizeLimitExceededException;

/**
 * HTTPS-only GET client for the download APIs.
 *
 * <ul>
 *   <li>Every URL, including each redirect target, must pass the {@link HostAllowlist} of the
 *       source. {@code http://} is refused, also as a redirect target.</li>
 *   <li>At most {@value #MAX_REDIRECTS} redirects.</li>
 *   <li>A size limit per request, checked against Content-Length and while reading.</li>
 *   <li>A time limit per attempt for headers and body together.</li>
 *   <li>Retries with exponential backoff on network errors and 5xx. Never on 4xx, never on a
 *       refused host, never on an exceeded size limit.</li>
 *   <li>429 and 503 are rate limits: up to {@value #MAX_RATE_LIMIT_RETRIES} retries of their own,
 *       waiting as long as {@code Retry-After} asks, or the normal backoff without that header.
 *       A {@code Retry-After} above {@link #MAX_RETRY_AFTER} fails at once.</li>
 * </ul>
 */
public final class DownloadClient {

    /** Highest number of redirects followed for one request. */
    public static final int MAX_REDIRECTS = 5;

    /** Highest number of retries after 429 or 503, counted apart from the normal attempts. */
    public static final int MAX_RATE_LIMIT_RETRIES = 2;

    /** Longest {@code Retry-After} the client waits for; a longer one fails the request. */
    public static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(60);

    private static final Set<Integer> REDIRECT_CODES = Set.of(301, 302, 303, 307, 308);

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "vectora-download-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    /** Reads a response body. Called once per attempt, so it must start from scratch each time. */
    @FunctionalInterface
    public interface BodyHandler<T> {

        /**
         * Consumes the body.
         *
         * @param body the body, limited in size and time
         * @return the result of the request
         * @throws IOException if reading or storing fails
         */
        T handle(InputStream body) throws IOException;
    }

    /** Waits between attempts. Replaced in tests so they do not sleep. */
    @FunctionalInterface
    public interface Sleeper {

        /**
         * Waits.
         *
         * @param duration how long
         * @throws InterruptedException if interrupted
         */
        void sleep(Duration duration) throws InterruptedException;
    }

    private final HttpTransport transport;
    private final DownloadSettings settings;
    private final Sleeper sleeper;
    private final Clock clock;

    /**
     * Creates a client that really waits between attempts.
     *
     * @param transport the HTTP transport
     * @param settings timeouts, retries, User-Agent
     */
    public DownloadClient(HttpTransport transport, DownloadSettings settings) {
        this(transport, settings, duration -> Thread.sleep(duration.toMillis()));
    }

    /**
     * Creates a client.
     *
     * @param transport the HTTP transport
     * @param settings timeouts, retries, User-Agent
     * @param sleeper waits between attempts
     */
    public DownloadClient(HttpTransport transport, DownloadSettings settings, Sleeper sleeper) {
        this(transport, settings, sleeper, Clock.systemUTC());
    }

    /**
     * Creates a client with its own clock, which turns a {@code Retry-After} date into a wait.
     *
     * @param transport the HTTP transport
     * @param settings timeouts, retries, User-Agent
     * @param sleeper waits between attempts
     * @param clock the current time
     */
    public DownloadClient(HttpTransport transport, DownloadSettings settings, Sleeper sleeper, Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Creates a client on the JDK HTTP client with {@link DownloadSettings#defaults()}.
     *
     * @return the client
     */
    public static DownloadClient createDefault() {
        return new DownloadClient(new JdkHttpTransport(DownloadSettings.CONNECT_TIMEOUT), DownloadSettings.defaults());
    }

    /**
     * Returns the settings.
     *
     * @return the settings
     */
    public DownloadSettings settings() {
        return settings;
    }

    /**
     * Fetches a small response, typically API JSON, into memory.
     *
     * @param uri the URL
     * @param allowlist the hosts of this source
     * @param maxBytes largest accepted body
     * @return the body
     * @throws IOException on failure after all attempts; a {@link DownloadException} says why
     * @throws InterruptedException if interrupted
     */
    public byte[] fetchBytes(URI uri, HostAllowlist allowlist, long maxBytes) throws IOException, InterruptedException {
        return get(uri, allowlist, maxBytes, body -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            body.transferTo(out);
            return out.toByteArray();
        });
    }

    /**
     * Sends a GET request and hands the body to {@code handler}, with retries.
     *
     * @param uri the URL
     * @param allowlist the hosts of this source
     * @param maxBytes largest accepted body
     * @param handler consumes the body; called again from scratch on a retry
     * @param <T> the result type
     * @return the handler's result
     * @throws IOException on failure after all attempts; a {@link DownloadException} says why
     * @throws InterruptedException if interrupted
     */
    public <T> T get(URI uri, HostAllowlist allowlist, long maxBytes, BodyHandler<T> handler)
            throws IOException, InterruptedException {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(allowlist, "allowlist");
        Objects.requireNonNull(handler, "handler");
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must not be negative");
        }
        int failures = 0;
        int rateLimited = 0;
        int retries = 0;
        while (true) {
            IOException failure;
            try {
                return attempt(uri, allowlist, maxBytes, handler);
            } catch (HttpStatusException e) {
                if (isRateLimit(e.status())) {
                    retries++;
                    sleeper.sleep(rateLimitWait(uri, e, rateLimited, retries + 1));
                    rateLimited++;
                    continue;
                }
                if (e.status() < 500) {
                    throw e;
                }
                failure = e;
            } catch (DownloadException e) {
                throw e;
            } catch (IOException e) {
                failure = e;
            }
            failures++;
            if (failures >= settings.maxAttempts()) {
                throw failure;
            }
            retries++;
            sleeper.sleep(settings.backoffBefore(retries + 1));
        }
    }

    private static boolean isRateLimit(int status) {
        return status == 429 || status == 503;
    }

    /**
     * Decides how long to wait after a 429 or 503, or gives up. The messages name only the host,
     * never the path or query of the URL.
     */
    private Duration rateLimitWait(URI uri, HttpStatusException e, int rateLimited, int nextAttempt)
            throws HttpStatusException {
        Optional<Duration> retryAfter = e.retryAfter();
        if (rateLimited >= MAX_RATE_LIMIT_RETRIES) {
            throw new HttpStatusException("HTTP " + e.status() + " from " + uri.getHost() + ": still rate limited after "
                    + MAX_RATE_LIMIT_RETRIES + " retries", e.status(), retryAfter);
        }
        if (retryAfter.isPresent() && retryAfter.get().compareTo(MAX_RETRY_AFTER) > 0) {
            throw new HttpStatusException("HTTP " + e.status() + " from " + uri.getHost() + ": the server asks to retry after "
                    + retryAfter.get().toSeconds() + " s, longer than the " + MAX_RETRY_AFTER.toSeconds()
                    + " s this client waits", e.status(), retryAfter);
        }
        return retryAfter.orElse(settings.backoffBefore(nextAttempt));
    }

    /**
     * Parses a {@code Retry-After} header: whole seconds (at most nine digits) or an HTTP date.
     *
     * @param value the header value, or {@code null}
     * @param clock the current time, for a date
     * @return the wait, never negative; empty if missing or not valid
     */
    static Optional<Duration> parseRetryAfter(String value, Clock clock) {
        if (value == null) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        try {
            if (!trimmed.isEmpty() && trimmed.length() <= 9 && trimmed.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return Optional.of(Duration.ofSeconds(Long.parseLong(trimmed)));
            }
            ZonedDateTime date = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
            Duration wait = Duration.between(clock.instant(), date.toInstant());
            return Optional.of(wait.isNegative() ? Duration.ZERO : wait);
        } catch (NumberFormatException | DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private <T> T attempt(URI uri, HostAllowlist allowlist, long maxBytes, BodyHandler<T> handler)
            throws IOException, InterruptedException {
        Map<String, String> headers = Map.of("User-Agent", settings.userAgent());
        URI current = uri;
        for (int hop = 0; ; hop++) {
            if (!allowlist.permits(current)) {
                throw new HostNotAllowedException(hop == 0
                        ? "Refusing " + current + ": only HTTPS to " + allowlist.hosts() + " is allowed"
                        : "Refusing redirect to " + current + " (from " + uri + "): only HTTPS to "
                                + allowlist.hosts() + " is allowed");
            }
            try (TransportResponse response = transport.get(current, headers, settings.requestTimeout())) {
                int status = response.status();
                if (REDIRECT_CODES.contains(status)) {
                    if (hop >= MAX_REDIRECTS) {
                        throw new DownloadException("More than " + MAX_REDIRECTS + " redirects for " + uri);
                    }
                    current = redirectTarget(current, response);
                    continue;
                }
                if (status < 200 || status > 299) {
                    throw new HttpStatusException(current, status,
                            parseRetryAfter(response.header("Retry-After").orElse(null), clock));
                }
                OptionalLong length = response.contentLength();
                if (length.isPresent() && length.getAsLong() > maxBytes) {
                    throw new SizeLimitExceededException(current + " announces " + length.getAsLong()
                            + " bytes, the limit is " + maxBytes);
                }
                return readBody(current, response, maxBytes, handler);
            }
        }
    }

    private <T> T readBody(URI uri, TransportResponse response, long maxBytes, BodyHandler<T> handler)
            throws IOException {
        Duration timeout = settings.transferTimeout();
        long deadline = System.nanoTime() + timeout.toNanos();
        // A stalled connection blocks read() forever; closing the stream from outside ends it.
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> closeQuietly(response), timeout.toMillis(),
                TimeUnit.MILLISECONDS);
        try (InputStream body = new GuardedInputStream(response.body(), uri, maxBytes, deadline, timeout)) {
            return handler.handle(body);
        } finally {
            watchdog.cancel(false);
        }
    }

    private static URI redirectTarget(URI current, TransportResponse response) throws DownloadException {
        String location = response.header("Location")
                .orElseThrow(() -> new DownloadException("Redirect without Location from " + current));
        try {
            return current.resolve(location.trim());
        } catch (IllegalArgumentException e) {
            throw new DownloadException("Malformed redirect target from " + current, e);
        }
    }

    private static void closeQuietly(TransportResponse response) {
        try {
            response.close();
        } catch (IOException ignored) {
            // The attempt fails with a timeout anyway.
        }
    }

    /** Enforces the size limit and the deadline on every read. */
    private static final class GuardedInputStream extends InputStream {

        private final InputStream in;
        private final URI uri;
        private final long maxBytes;
        private final long deadline;
        private final Duration timeout;
        private long count;

        GuardedInputStream(InputStream in, URI uri, long maxBytes, long deadline, Duration timeout) {
            this.in = in;
            this.uri = uri;
            this.maxBytes = maxBytes;
            this.deadline = deadline;
            this.timeout = timeout;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            checkDeadline();
            int n;
            try {
                n = in.read(buffer, offset, length);
            } catch (IOException e) {
                checkDeadline();
                throw e;
            }
            if (n > 0) {
                count += n;
                if (count > maxBytes) {
                    throw new SizeLimitExceededException(uri + " sent more than " + maxBytes + " bytes");
                }
            }
            return n;
        }

        private void checkDeadline() throws IOException {
            if (System.nanoTime() - deadline > 0) {
                throw new java.net.http.HttpTimeoutException("Transfer of " + uri + " took longer than " + timeout);
            }
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
