package net.vectora.cloud.api.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import net.vectora.cloud.api.auth.Scope;

/**
 * Maps a method and a raw request target to a route. Strict, because the target comes straight
 * from the network:
 * <ul>
 *   <li>origin form only ({@code /api/v1/...}), at most {@value #MAX_TARGET} characters, printable ASCII;</li>
 *   <li>no empty segments, no {@code .} or {@code ..}, no percent-encoding in the path;</li>
 *   <li>each parameter matches its pattern ({@code {id}}: service id, {@code {name}}: group or
 *       task name, {@code {kind}}: server kind);</li>
 *   <li>query parameters are {@code key=value} pairs with lower-case keys, each key once, values
 *       percent-decoded to letters, digits and {@code ._,-}.</li>
 * </ul>
 * Anything else is a {@link Resolution.Invalid}, never an exception.
 *
 * @param <H> the handler type
 */
public final class Router<H> {

    /** Longest request target. */
    public static final int MAX_TARGET = 2048;

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_-]{1,64}(\\.[A-Za-z0-9_-]{1,64})?");
    private static final Pattern QUERY_KEY = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Pattern QUERY_VALUE = Pattern.compile("[A-Za-z0-9._,-]{0,512}");
    private static final int MAX_QUERY_PARAMS = 16;

    private static final Map<String, Pattern> PARAMETERS = Map.of(
            "id", Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}"),
            "name", Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}"),
            "kind", Pattern.compile("[a-z]{1,16}"));

    private final List<Route<H>> routes = new ArrayList<>();

    /**
     * Adds a route.
     *
     * @param method  HTTP method in upper case
     * @param path    template such as {@code /api/v1/services/{id}/stop}
     * @param scope   required scope, or null if any valid token may call it
     * @param handler the handler
     * @return this router
     */
    public Router<H> add(String method, String path, Scope scope, H handler) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(handler, "handler");
        String[] parts = path.substring(1).split("/", -1);
        for (String part : parts) {
            if (part.startsWith("{") && !PARAMETERS.containsKey(part.substring(1, part.length() - 1))) {
                throw new IllegalArgumentException("unknown parameter " + part);
            }
        }
        routes.add(new Route<>(method, path, List.of(parts), scope, handler));
        return this;
    }

    /**
     * Every route, in the order added.
     *
     * @return the routes
     */
    public List<Route<H>> routes() {
        return Collections.unmodifiableList(routes);
    }

    /**
     * Finds the route of a request.
     *
     * @param method    the HTTP method
     * @param rawTarget the request target as sent ({@code /api/v1/groups/lobby?confirm=lobby})
     * @return what was found; never null, never throws for any input
     */
    public Resolution<H> resolve(String method, String rawTarget) {
        if (method == null || rawTarget == null || rawTarget.length() > MAX_TARGET || !rawTarget.startsWith("/")) {
            return new Resolution.Invalid<>("invalid-target");
        }
        for (int i = 0; i < rawTarget.length(); i++) {
            char c = rawTarget.charAt(i);
            if (c <= 0x20 || c >= 0x7f) {
                return new Resolution.Invalid<>("invalid-target");
            }
        }
        int q = rawTarget.indexOf('?');
        String path = q < 0 ? rawTarget : rawTarget.substring(0, q);
        String query = q < 0 ? null : rawTarget.substring(q + 1);
        if (rawTarget.indexOf('#') >= 0) {
            return new Resolution.Invalid<>("invalid-target");
        }
        String[] segments = path.substring(1).split("/", -1);
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()) {
                return new Resolution.Invalid<>("invalid-path");
            }
        }
        Map<String, String> queryParams;
        try {
            queryParams = parseQuery(query);
        } catch (IllegalArgumentException e) {
            return new Resolution.Invalid<>("invalid-query");
        }
        Set<String> allowed = new LinkedHashSet<>();
        boolean badParameter = false;
        for (Route<H> route : routes) {
            Map<String, String> params = match(route, segments);
            if (params == null) {
                continue;
            }
            if (params.containsKey(null)) {
                badParameter = true;
                continue;
            }
            if (!route.method().equals(method)) {
                allowed.add(route.method());
                continue;
            }
            params.remove(null);
            return new Resolution.Found<>(route, Collections.unmodifiableMap(params), queryParams);
        }
        if (!allowed.isEmpty()) {
            return new Resolution.MethodNotAllowed<>(Set.copyOf(allowed));
        }
        return badParameter ? new Resolution.Invalid<>("invalid-path") : new Resolution.NotFound<>();
    }

    /**
     * Path parameters of {@code route} for {@code segments}, or null if the route does not fit.
     * A parameter that fails its pattern is stored under the key null.
     */
    private Map<String, String> match(Route<H> route, String[] segments) {
        if (route.segments().size() != segments.length) {
            return null;
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < segments.length; i++) {
            String template = route.segments().get(i);
            if (template.startsWith("{")) {
                String name = template.substring(1, template.length() - 1);
                if (PARAMETERS.get(name).matcher(segments[i]).matches()) {
                    params.put(name, segments[i]);
                } else {
                    params.put(null, "");
                }
            } else if (!template.equals(segments[i])) {
                return null;
            }
        }
        return params;
    }

    private static Map<String, String> parseQuery(String query) {
        if (query == null || query.isEmpty()) {
            return Map.of();
        }
        String[] pairs = query.split("&", -1);
        if (pairs.length > MAX_QUERY_PARAMS) {
            throw new IllegalArgumentException("too many parameters");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            if (!QUERY_KEY.matcher(key).matches() || !QUERY_VALUE.matcher(value).matches()
                    || result.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("invalid parameter");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /** Percent-decodes ASCII; {@code +} stays {@code +} (and is refused by the value pattern). */
    private static String decode(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%') {
                if (i + 2 >= value.length()) {
                    throw new IllegalArgumentException("broken escape");
                }
                int hi = Character.digit(value.charAt(i + 1), 16);
                int lo = Character.digit(value.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    throw new IllegalArgumentException("broken escape");
                }
                out.append((char) (hi * 16 + lo));
                i += 2;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * One route.
     *
     * @param method   HTTP method
     * @param path     the template
     * @param segments the template split at {@code /}
     * @param scope    required scope, or null
     * @param handler  the handler
     * @param <H>      the handler type
     */
    public record Route<H>(String method, String path, List<String> segments, Scope scope, H handler) {
    }

    /**
     * Result of {@link #resolve}.
     *
     * @param <H> the handler type
     */
    public sealed interface Resolution<H> {

        /**
         * A route fits.
         *
         * @param route  the route
         * @param params path parameters by name
         * @param query  query parameters by name
         * @param <H>    the handler type
         */
        record Found<H>(Route<H> route, Map<String, String> params, Map<String, String> query) implements Resolution<H> {
        }

        /**
         * The path exists, but not for this method.
         *
         * @param allowed the methods it has
         * @param <H>     the handler type
         */
        record MethodNotAllowed<H>(Set<String> allowed) implements Resolution<H> {
        }

        /**
         * No route has this path.
         *
         * @param <H> the handler type
         */
        record NotFound<H>() implements Resolution<H> {
        }

        /**
         * The target breaks a rule.
         *
         * @param code {@code invalid-target}, {@code invalid-path} or {@code invalid-query}
         * @param <H>  the handler type
         */
        record Invalid<H>(String code) implements Resolution<H> {
        }
    }
}
