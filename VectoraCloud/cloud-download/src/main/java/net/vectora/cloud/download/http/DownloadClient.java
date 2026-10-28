package net.vectora.cloud.download.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
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
 * </ul>
 */
public final class DownloadClient {

    /** Highest number of redirects followed for one request. */
    public static final int MAX_REDIRECTS = 5;

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
         * @param contentLength the announced length, if any
         * @return the result of the request
         * @throws IOException if reading or storing fails
         */
        T handle(InputStream body, OptionalLong contentLength) throws IOException;
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
        this.transport = Objects.requireNonNull(transport, "transport");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
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
        return get(uri, allowlist, maxBytes, (body, length) -> {
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
        IOException last = null;
        for (int attempt = 1; attempt <= settings.maxAttempts(); attempt++) {
            if (attempt > 1) {
                sleeper.sleep(settings.backoffBefore(attempt));
            }
            try {
                return attempt(uri, allowlist, maxBytes, handler);
            } catch (HttpStatusException e) {
                if (e.status() < 500) {
                    throw e;
                }
                last = e;
            } catch (DownloadException e) {
                throw e;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
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
                    throw new HttpStatusException(current, status);
                }
                OptionalLong length = response.contentLength();
                if (length.isPresent() && length.getAsLong() > maxBytes) {
                    throw new SizeLimitExceededException(current + " announces " + length.getAsLong()
                            + " bytes, the limit is " + maxBytes);
                }
                return readBody(current, response, maxBytes, length, handler);
            }
        }
    }

    private <T> T readBody(URI uri, TransportResponse response, long maxBytes, OptionalLong length,
            BodyHandler<T> handler) throws IOException {
        Duration timeout = settings.transferTimeout();
        long deadline = System.nanoTime() + timeout.toNanos();
        // A stalled connection blocks read() forever; closing the stream from outside ends it.
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> closeQuietly(response), timeout.toMillis(),
                TimeUnit.MILLISECONDS);
        try (InputStream body = new GuardedInputStream(response.body(), uri, maxBytes, deadline, timeout)) {
            return handler.handle(body, length);
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
