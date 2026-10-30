package net.vectolyn.cloud.download;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/** The server answered with a status code other than 2xx (after following allowed redirects). */
public final class HttpStatusException extends DownloadException {

    private static final long serialVersionUID = 2L;

    private final int status;
    private final Duration retryAfter;

    /**
     * Creates the exception.
     *
     * @param uri the requested URL
     * @param status the HTTP status code
     */
    public HttpStatusException(URI uri, int status) {
        this(uri, status, Optional.empty());
    }

    /**
     * Creates the exception with the server's {@code Retry-After}.
     *
     * @param uri the requested URL
     * @param status the HTTP status code
     * @param retryAfter how long the server asks to wait, if it said so
     */
    public HttpStatusException(URI uri, int status, Optional<Duration> retryAfter) {
        this("HTTP " + status + " for " + uri, status, retryAfter);
    }

    /**
     * Creates the exception with its own message.
     *
     * @param message the message
     * @param status the HTTP status code
     * @param retryAfter how long the server asks to wait, if it said so
     */
    public HttpStatusException(String message, int status, Optional<Duration> retryAfter) {
        super(message);
        this.status = status;
        this.retryAfter = retryAfter.orElse(null);
    }

    /**
     * Returns the HTTP status code.
     *
     * @return the status code
     */
    public int status() {
        return status;
    }

    /**
     * Returns how long the server asked to wait with {@code Retry-After}.
     *
     * @return the wait, or empty if the header was missing or not valid
     */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    /**
     * Tells whether the status is a client error (4xx). Apart from 429, those are never retried.
     *
     * @return {@code true} for 400 to 499
     */
    public boolean isClientError() {
        return status >= 400 && status < 500;
    }
}
