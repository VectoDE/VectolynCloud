// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import net.vectolyn.cloud.api.auth.ApiToken;
import net.vectolyn.cloud.api.auth.Scope;
import net.vectolyn.cloud.api.http.ApiException;
import net.vectolyn.cloud.api.http.ApiJson;
import net.vectolyn.cloud.api.http.ApiRequest;
import net.vectolyn.cloud.api.http.ApiResponse;
import net.vectolyn.cloud.api.http.ApiRoutes;
import net.vectolyn.cloud.api.http.Router;

/**
 * Runs a REST route for GraphQL and gRPC, the same way {@link ApiHttpHandler} does after
 * authentication: resolve the target, check the route's scope against the token, run the
 * handler, map a failure to its {@link ApiException}. The callers build the target only from
 * arguments that passed {@link #segment}, so nothing can leave its path segment or add a query.
 */
final class RouteCaller {

    private static final Pattern SEGMENT = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,127}");

    private final Router<ApiRoutes.Handler> router;
    private final Function<Exception, ApiException> errors;

    RouteCaller(Router<ApiRoutes.Handler> router, Function<Exception, ApiException> errors) {
        this.router = Objects.requireNonNull(router, "router");
        this.errors = Objects.requireNonNull(errors, "errors");
    }

    /**
     * Runs a route.
     *
     * @param token   the caller
     * @param method  HTTP method
     * @param target  path below {@code /api/v1}, with query
     * @param body    a value written as the JSON body, or null for none
     * @param ifMatch the {@code If-Match} value, or null
     * @return the answer
     * @throws ApiException the error REST would answer
     */
    ApiResponse call(ApiToken token, String method, String target, Object body, String ifMatch) throws ApiException {
        Router.Resolution<ApiRoutes.Handler> resolution = router.resolve(method, ApiRoutes.PREFIX + target);
        if (!(resolution instanceof Router.Resolution.Found<ApiRoutes.Handler> found)) {
            throw new ApiException(400, "invalid-value", "An argument breaks a rule of the API.");
        }
        Scope scope = found.route().scope();
        if (scope != null && !token.allows(scope)) {
            throw new ApiException(403, "missing-scope", "This token lacks the scope " + scope.id() + ".");
        }
        byte[] json = body == null ? new byte[0] : ApiJson.write(body);
        ApiRequest request = new ApiRequest(method, found.params(), found.query(), json, token, ifMatch);
        try {
            return found.route().handler().handle(request);
        } catch (Exception e) {
            throw errors.apply(e);
        }
    }

    /**
     * Checks a value that becomes a path segment or a query value: a name or id.
     *
     * @param value the value
     * @param name  the argument, for the message
     * @return the value
     * @throws ApiException 400 {@code invalid-value}
     */
    static String segment(Object value, String name) throws ApiException {
        if (!(value instanceof String text) || !SEGMENT.matcher(text).matches()) {
            throw new ApiException(400, "invalid-value", "\"" + name + "\" must be a name or id "
                    + "(lower-case letters, digits, '.', '_', '-').");
        }
        return text;
    }

    /**
     * Checks an {@code If-Match} value.
     *
     * @param value the value, or null
     * @return the value, null for none or empty
     * @throws ApiException 400 {@code invalid-value}
     */
    static String ifMatch(String value) throws ApiException {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.length() > 128 || value.chars().anyMatch(c -> c < 0x20 || c > 0x7e)) {
            throw new ApiException(400, "invalid-value", "\"ifMatch\" must be an ETag.");
        }
        return value;
    }

    /**
     * The JSON body of an answer as maps and lists.
     *
     * @param response the answer
     * @return the value, an empty map for no body
     */
    static Object json(ApiResponse response) {
        if (response.body() == null) {
            return java.util.Map.of();
        }
        return ApiJson.mapper().readValue(response.body(), Object.class);
    }
}
