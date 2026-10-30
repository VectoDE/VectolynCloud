package net.vectolyn.cloud.api.http;

import java.util.Map;

/**
 * A successful answer.
 *
 * @param status  HTTP status
 * @param body    JSON body, or null for none (204)
 * @param headers extra headers, for example {@code ETag}
 */
public record ApiResponse(int status, byte[] body, Map<String, String> headers) {

    /** Copies the headers. */
    public ApiResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
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
