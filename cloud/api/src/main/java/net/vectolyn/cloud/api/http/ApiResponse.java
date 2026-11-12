// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api.http;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A successful answer.
 *
 * @param status  HTTP status
 * @param body    JSON body, or null for none (204)
 * @param headers extra headers, for example {@code ETag}
 */
// The body is the encoded JSON; copying it into a List would cost every response, so the array stays.
@SuppressWarnings("ArrayRecordComponent")
public record ApiResponse(int status, byte @Nullable [] body, Map<String, String> headers) {

    /**
     * Copies the headers.
     *
     * <p>Written out instead of a compact constructor: for a compact one javac copies the
     * {@code @Nullable} of the component onto the constructor with target type FIELD, which PMD
     * cannot read ("Invalid target type of type annotation"), so PMD lost the types of every
     * {@code new ApiResponse(...)}.
     *
     * @param status  HTTP status
     * @param body    JSON body, or null for none (204)
     * @param headers extra headers, for example {@code ETag}
     */
    public ApiResponse(int status, byte @Nullable [] body, Map<String, String> headers) {
        this.status = status;
        this.body = body;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /**
     * 200 with a JSON body.
     *
     * @param value the value
     * @return the answer
     */
    public static ApiResponse ok(Object value) {
        return new ApiResponse(200, ApiJson.write(value), Map.of());
    }

    /**
     * A status with a JSON body.
     *
     * @param status the status
     * @param value  the value
     * @return the answer
     */
    public static ApiResponse of(int status, Object value) {
        return new ApiResponse(status, ApiJson.write(value), Map.of());
    }

    /**
     * A document with its {@code ETag}.
     *
     * @param status the status
     * @param value  the document
     * @return the answer
     */
    public static ApiResponse document(int status, Object value) {
        byte[] json = ApiJson.write(value);
        return new ApiResponse(status, json, Map.of("ETag", ApiJson.etag(json)));
    }

    /**
     * 204 without a body.
     *
     * @return the answer
     */
    public static ApiResponse noContent() {
        return new ApiResponse(204, null, Map.of());
    }
}
