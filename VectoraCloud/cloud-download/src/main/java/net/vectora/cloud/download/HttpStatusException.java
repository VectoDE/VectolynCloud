package net.vectora.cloud.download;

import java.net.URI;

/** The server answered with a status code other than 2xx (after following allowed redirects). */
public final class HttpStatusException extends DownloadException {

    private static final long serialVersionUID = 1L;

    private final int status;

    /**
     * Creates the exception.
     *
     * @param uri the requested URL
     * @param status the HTTP status code
     */
    public HttpStatusException(URI uri, int status) {
        super("HTTP " + status + " for " + uri);
        this.status = status;
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
     * Tells whether the status is a client error (4xx). Those are never retried.
     *
     * @return {@code true} for 400 to 499
     */
    public boolean isClientError() {
        return status >= 400 && status < 500;
    }
}
