package net.vectolyn.cloud.api.http;

import java.util.Map;
import java.util.Optional;
import net.vectolyn.cloud.api.auth.ApiToken;

/**
 * A request that passed authentication, rate limits, routing and the scope check.
 *
 * @param method  HTTP method
 * @param params  path parameters (already matched against their patterns)
 * @param query   query parameters
 * @param body    the body, empty array if there is none
 * @param token   the token that made the request
 * @param ifMatch the {@code If-Match} header, or null
 */
public record ApiRequest(String method, Map<String, String> params, Map<String, String> query, byte[] body,
                         ApiToken token, String ifMatch) {

    /**
     * A path parameter.
     *
     * @param name {@code id}, {@code name} or {@code kind}
     * @return the value
     */
    public String param(String name) {
        return params.get(name);
    }

    /**
     * A query parameter.
     *
     * @param name the key
     * @return the value, or empty
     */
    public Optional<String> query(String name) {
        return Optional.ofNullable(query.get(name));
    }

    /**
     * Reads the body as JSON.
     *
     * @param type the record
     * @param <T>  the type
     * @return the value
     * @throws ApiException 400 if the body is missing or not valid for {@code type}
     */
    public <T> T json(Class<T> type) throws ApiException {
        return ApiJson.read(body, type);
    }
}
