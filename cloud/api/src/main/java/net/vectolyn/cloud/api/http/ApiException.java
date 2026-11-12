// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api.http;

import java.io.Serial;
import java.util.List;
import java.util.Objects;
import net.vectolyn.cloud.config.error.ConfigError;
import net.vectolyn.cloud.config.error.ErrorCode;
import org.jspecify.annotations.Nullable;

/**
 * An error answer: HTTP status, a stable code and an English message. The message is built by the
 * API from fixed texts and checked names; it never repeats a value from the request, so a secret
 * pasted into the wrong field does not come back or reach a log.
 */
public final class ApiException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;
    private final transient List<ConfigError> errors;
    private final long retryAfterSeconds;

    /**
     * Creates an error.
     *
     * @param status  HTTP status
     * @param code    stable code, for example {@code unknown-field}
     * @param message English text without values from the request
     */
    public ApiException(int status, String code, String message) {
        this(status, code, message, List.of(), 0, null);
    }

    /**
     * Creates an error with the failure behind it. The cause stays on the server and is not sent.
     *
     * @param status  HTTP status
     * @param code    stable code, for example {@code unknown-field}
     * @param message English text without values from the request
     * @param cause   the failure behind it
     */
    public ApiException(int status, String code, String message, Throwable cause) {
        this(status, code, message, List.of(), 0, cause);
    }

    private ApiException(int status, String code, String message, List<ConfigError> errors, long retryAfterSeconds,
            @Nullable Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause, false, false);
        this.status = status;
        this.code = Objects.requireNonNull(code, "code");
        this.errors = List.copyOf(errors);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * 422 with the errors of {@code ConfigService.apply}.
     *
     * @param errors the configuration errors
     * @return the exception
     */
    public static ApiException invalidConfig(List<ConfigError> errors) {
        return new ApiException(422, "invalid-config", "The configuration was not changed: it would be invalid.",
                errors, 0, null);
    }

    /**
     * 422 with the errors of {@code ConfigService.apply}, and the failure they came from.
     *
     * @param errors the configuration errors
     * @param cause  the failure behind it
     * @return the exception
     */
    public static ApiException invalidConfig(List<ConfigError> errors, Throwable cause) {
        return new ApiException(422, "invalid-config", "The configuration was not changed: it would be invalid.",
                errors, 0, cause);
    }

    /**
     * 429 with a {@code Retry-After} header.
     *
     * @param seconds seconds until the client may try again
     * @return the exception
     */
    public static ApiException tooManyRequests(long seconds) {
        return new ApiException(429, "rate-limited", "Too many requests. Try again later.", List.of(),
                Math.max(1, seconds), null);
    }

    /** 400 with a code and message. */
    static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    /** 400 with a code and message, and the failure behind it. */
    static ApiException badRequest(String code, String message, Throwable cause) {
        return new ApiException(400, code, message, cause);
    }

    /**
     * 400 for a body that cannot be read as the document, with the error of the strict reader
     * (field path, line in the body, a code such as {@code required}). The code of the answer is
     * {@code unknown-field} for an unknown field and {@code invalid-json} for anything else.
     */
    static ApiException invalidBody(List<ConfigError> errors, Throwable cause) {
        boolean unknown = !errors.isEmpty()
                && errors.getFirst().code() == ErrorCode.UNKNOWN_FIELD;
        return new ApiException(400, unknown ? "unknown-field" : "invalid-json",
                "The body is not a valid document; see errors.", errors, 0, cause);
    }

    /** 404. */
    static ApiException notFound(String what) {
        return new ApiException(404, "not-found", what + " does not exist.");
    }

    /** 404, with the failure behind it. */
    static ApiException notFound(String what, Throwable cause) {
        return new ApiException(404, "not-found", what + " does not exist.", cause);
    }

    /**
     * 503 while the node shuts down.
     *
     * @return the exception
     */
    public static ApiException shuttingDown() {
        return new ApiException(503, "shutting-down", "The node is shutting down.");
    }

    /**
     * HTTP status.
     *
     * @return the status
     */
    public int status() {
        return status;
    }

    /**
     * Stable code.
     *
     * @return the code
     */
    public String code() {
        return code;
    }

    /**
     * Configuration errors ({@code invalid-config}, and a body the strict reader refused).
     *
     * @return the errors, possibly empty
     */
    public List<ConfigError> errors() {
        return errors;
    }

    /**
     * Seconds for {@code Retry-After}, 0 if none.
     *
     * @return the seconds
     */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
