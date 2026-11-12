// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.DataFetcherResult;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.introspection.Introspection;
import graphql.language.ArrayValue;
import graphql.language.BooleanValue;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.NullValue;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.parser.ParserOptions;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetcherFactories;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.vectolyn.cloud.api.auth.ApiToken;
import net.vectolyn.cloud.api.auth.Scope;
import net.vectolyn.cloud.api.http.ApiException;
import net.vectolyn.cloud.api.http.ApiJson;
import net.vectolyn.cloud.api.http.ApiRequest;
import net.vectolyn.cloud.api.http.ApiResponse;
import net.vectolyn.cloud.api.http.ApiRoutes;
import net.vectolyn.cloud.api.http.Router;
import org.reactivestreams.Publisher;

/**
 * The GraphQL endpoint ({@code POST /api/v1/graphql}, schema in {@code schema.graphqls}).
 *
 * <p>GraphQL is a second way into the same endpoints, not a second implementation: every field
 * builds the REST request named in its description and runs the route of {@link ApiRoutes} with
 * the caller's token. Scope checks, input validation, {@code If-Match} and error codes are
 * therefore those of REST; an error becomes a GraphQL error with {@code extensions.code} and
 * {@code extensions.status}.
 *
 * <p>Limits against expensive documents, all checked before a field runs:
 * <ul>
 *   <li>parser: at most {@value #MAX_CHARACTERS} characters, {@value #MAX_TOKENS} tokens and a
 *       grammar depth of {@value #MAX_RULE_DEPTH};</li>
 *   <li>query depth at most {@value #MAX_DEPTH}, complexity (one per field) at most
 *       {@value #MAX_COMPLEXITY};</li>
 *   <li>every REST call after the first takes one more request from the token's rate limit, and
 *       a document makes at most {@value #MAX_CALLS} of them.</li>
 * </ul>
 * Introspection is off unless {@code api.graphql-introspection} is set in {@code cloud.yml}.
 */
final class GraphQlApi {

    /** Path of the endpoint (HTTP POST and the WebSocket). */
    static final String PATH = ApiRoutes.GRAPHQL;
    /** Longest document. */
    static final int MAX_CHARACTERS = 16 * 1024;
    /** Most parser tokens of a document. */
    static final int MAX_TOKENS = 2_000;
    /** Deepest grammar nesting the parser accepts. */
    static final int MAX_RULE_DEPTH = 100;
    /** Deepest selection. */
    static final int MAX_DEPTH = 8;
    /** Most fields of a document. */
    static final int MAX_COMPLEXITY = 200;
    /** Most REST calls one document may make. */
    static final int MAX_CALLS = 20;

    private static final Pattern ARGUMENT = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,127}");
    private static final Pattern NEXT = Pattern.compile("[?&]after=([^&>]+)");
    private static final ParserOptions PARSER = ParserOptions.getDefaultOperationParserOptions().transform(b -> b
            .maxCharacters(MAX_CHARACTERS)
            .maxTokens(MAX_TOKENS)
            .maxWhitespaceTokens(MAX_TOKENS * 10)
            .maxRuleDepth(MAX_RULE_DEPTH)
            .maxNumericLiteralCharacters(32)
            .captureSourceLocation(true)
            .captureIgnoredChars(false)
            .captureLineComments(false)
            .redactTokenParserErrorMessages(true));

    private final Router<ApiRoutes.Handler> router;
    private final Function<Exception, ApiException> errors;
    private final TokenCharge charge;
    private final boolean introspection;
    private final GraphQL graphQl;

    /** Takes one request from the token's rate limit; answers the seconds to wait, 0 if allowed. */
    @FunctionalInterface
    interface TokenCharge {
        long tryAcquire(String tokenId);
    }

    /**
     * Creates the endpoint.
     *
     * @param router        the REST routes
     * @param errors        maps a failure of a route to its API error (redacted)
     * @param charge        the token's rate limit
     * @param introspection whether {@code __schema} and {@code __type} are answered
     */
    GraphQlApi(Router<ApiRoutes.Handler> router, Function<Exception, ApiException> errors, TokenCharge charge,
               boolean introspection) {
        this.router = Objects.requireNonNull(router, "router");
        this.errors = Objects.requireNonNull(errors, "errors");
        this.charge = Objects.requireNonNull(charge, "charge");
        this.introspection = introspection;
        this.graphQl = GraphQL.newGraphQL(schema())
                .instrumentation(new ChainedInstrumentation(
                        new MaxQueryDepthInstrumentation(MAX_DEPTH),
                        new MaxQueryComplexityInstrumentation(MAX_COMPLEXITY)))
                .build();
    }

    /** Opens the event publisher of a subscription; one per WebSocket connection. */
    @FunctionalInterface
    interface Subscriptions {

        /**
         * A publisher of {@code Event} objects for one subscription.
         *
         * @param token    the subscriber's token
         * @param types    event types asked for (empty: every type the token may see)
         * @param services service ids to filter on (empty: all)
         * @return the publisher
         * @throws ApiException 400 or 403 for types the token may not see, 503 when too many
         *                      streams are open
         */
        Publisher<Map<String, Object>> open(ApiToken token, List<String> types, List<String> services)
                throws ApiException;
    }

    /** Whether introspection is answered. */
    boolean introspection() {
        return introspection;
    }

    /**
     * Runs one GraphQL request.
     *
     * @param token     the caller
     * @param query     the document
     * @param operation the operation to run, or null
     * @param variables     the variables, or null
     * @param subscriptions where a subscription registers, or null over HTTP
     * @return the result; a subscription has a {@link Publisher} as data
     */
    ExecutionResult execute(ApiToken token, String query, String operation, Map<String, Object> variables,
                            Subscriptions subscriptions) {
        Map<Object, Object> context = new LinkedHashMap<>();
        context.put(ParserOptions.class, PARSER);
        context.put(ApiToken.class, token);
        context.put(AtomicInteger.class, new AtomicInteger());
        if (subscriptions != null) {
            context.put(Subscriptions.class, subscriptions);
        }
        if (!introspection) {
            context.put(Introspection.INTROSPECTION_DISABLED, true);
        }
        ExecutionInput input = ExecutionInput.newExecutionInput()
                .query(query == null ? "" : query)
                .operationName(operation)
                .variables(variables == null ? Map.of() : variables)
                .graphQLContext(context)
                .build();
        return graphQl.execute(input);
    }

    /**
     * Reads the body of {@code POST /api/v1/graphql} ({@code query}, {@code operationName},
     * {@code variables}, {@code extensions}), runs it and answers 200 with
     * {@code {"data":...,"errors":[...]}}. A body that is not such an object is 400.
     *
     * @param request the request (already authenticated, no route scope)
     * @return the answer
     * @throws ApiException 400 for a body that is not a GraphQL request, or a subscription
     */
    ApiResponse handle(ApiRequest request) throws ApiException {
        Body body = Body.read(request.body());
        ExecutionResult result = execute(request.token(), body.query(), body.operationName(), body.variables(), null);
        if (result.getData() instanceof Publisher<?>) {
            throw bad("subscription-over-http",
                    "Subscriptions run over the WebSocket " + PATH + " (graphql-transport-ws).");
        }
        return ApiResponse.ok(result.toSpecification());
    }

    /** A GraphQL request body. */
    record Body(String query, String operationName, Map<String, Object> variables) {

        /**
         * Reads and checks a body.
         *
         * @param json UTF-8 JSON
         * @return the body
         * @throws ApiException 400 {@code invalid-graphql-request}
         */
        static Body read(byte[] json) throws ApiException {
            if (json == null || json.length == 0) {
                throw bad("body-required", "A JSON body is required.");
            }
            Object tree;
            try {
                tree = ApiJson.mapper().readValue(json, Object.class);
            } catch (RuntimeException e) {
                throw bad("invalid-json", "The body is not valid JSON.");
            }
            return of(tree);
        }

        /**
         * Checks a parsed request object (also the {@code payload} of a WebSocket subscribe).
         *
         * @param tree the parsed JSON
         * @return the body
         * @throws ApiException 400 {@code invalid-graphql-request}
         */
        static Body of(Object tree) throws ApiException {
            if (!(tree instanceof Map<?, ?> map)) {
                throw invalid("The body must be a JSON object.");
            }
            for (Object key : map.keySet()) {
                if (!List.of("query", "operationName", "variables", "extensions").contains(key)) {
                    throw invalid("Unknown member \"" + (String.valueOf(key).length() > 32 ? "..." : key) + "\".");
                }
            }
            if (!(map.get("query") instanceof String query) || query.isBlank()) {
                throw invalid("\"query\" must be a non-empty string.");
            }
            if (query.length() > MAX_CHARACTERS) {
                throw invalid("\"query\" is longer than " + MAX_CHARACTERS + " characters.");
            }
            Object operation = map.get("operationName");
            if (operation != null && !(operation instanceof String)) {
                throw invalid("\"operationName\" must be a string.");
            }
            Object variables = map.get("variables");
            if (variables != null && !(variables instanceof Map<?, ?>)) {
                throw invalid("\"variables\" must be an object.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> vars = (Map<String, Object>) variables;
            return new Body(query, (String) operation, vars);
        }

        private static ApiException invalid(String message) {
            return bad("invalid-graphql-request", message);
        }
    }

    /** A 400 error with a code. */
    static ApiException bad(String code, String message) {
        return new ApiException(400, code, message);
    }

    // ---------------------------------------------------------------- schema

    private GraphQLSchema schema() {
        TypeDefinitionRegistry types;
        try (InputStream in = GraphQlApi.class.getResourceAsStream("schema.graphqls")) {
            if (in == null) {
                throw new IllegalStateException("schema.graphqls is missing");
            }
            types = new SchemaParser().parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring()
                .scalar(JSON)
                .scalar(LONG)
                .codeRegistry(graphql.schema.GraphQLCodeRegistry.newCodeRegistry()
                        .defaultDataFetcher(DataFetcherFactories.useDataFetcher(GraphQlApi::kebabField)))
                .type("Query", t -> t
                        .dataFetcher("services", page("/services", Function.identity()))
                        .dataFetcher("service", get(e -> "/services/" + arg(e, "id")))
                        .dataFetcher("groups", page("/groups", GraphQlApi::entries))
                        .dataFetcher("group", document(e -> "/groups/" + arg(e, "name")))
                        .dataFetcher("tasks", page("/tasks", GraphQlApi::entries))
                        .dataFetcher("task", document(e -> "/tasks/" + arg(e, "name")))
                        .dataFetcher("nodes", page("/nodes", GraphQlApi::nodeEntries))
                        .dataFetcher("node", document(e -> "/nodes/" + arg(e, "name")))
                        .dataFetcher("servers", get(e -> "/servers"))
                        .dataFetcher("config", document(e -> "/config"))
                        .dataFetcher("configWarnings", get(e -> "/config/warnings"))
                        .dataFetcher("nodeStatus", get(e -> "/node"))
                        .dataFetcher("setup", get(e -> "/setup"))
                        .dataFetcher("backups", page("/backups", Function.identity()))
                        .dataFetcher("self", get(e -> "/tokens/self"))
                        .dataFetcher("tokens", page("/admin/tokens", Function.identity())))
                .type("Mutation", t -> t
                        .dataFetcher("startService", call("POST", e -> "/services", e -> Map.of("task", arg(e, "task"))))
                        .dataFetcher("stopService", call("POST", e -> "/services/" + arg(e, "id") + "/stop", e -> null))
                        .dataFetcher("restartService", call("POST", e -> "/services/" + arg(e, "id") + "/restart",
                                e -> null))
                        .dataFetcher("sendCommand", call("POST", e -> "/services/" + arg(e, "id") + "/command",
                                e -> Map.of("command", e.<String>getArgument("command"))))
                        .dataFetcher("putGroup", put(e -> "/groups/" + arg(e, "name")))
                        .dataFetcher("deleteGroup", delete(e -> "/groups/" + arg(e, "name")
                                + "?confirm=" + arg(e, "confirm")))
                        .dataFetcher("setGroupWanted", call("PUT", e -> "/groups/" + arg(e, "name") + "/wanted",
                                GraphQlApi::wanted))
                        .dataFetcher("putTask", put(e -> "/tasks/" + arg(e, "name")))
                        .dataFetcher("deleteTask", delete(e -> "/tasks/" + arg(e, "name")
                                + "?confirm=" + arg(e, "confirm")))
                        .dataFetcher("setTaskWanted", call("PUT", e -> "/tasks/" + arg(e, "name") + "/wanted",
                                GraphQlApi::wanted))
                        .dataFetcher("putNode", put(e -> "/nodes/" + arg(e, "name")))
                        .dataFetcher("setServerWanted", call("PUT", e -> "/servers/" + arg(e, "kind"),
                                GraphQlApi::wanted))
                        .dataFetcher("putConfig", put(e -> "/config"))
                        .dataFetcher("reloadConfig", call("POST", e -> "/config/reload", e -> null))
                        .dataFetcher("createBackup", call("POST", e -> "/backups", GraphQlApi::backupOrder))
                        .dataFetcher("createToken", call("POST", e -> "/admin/tokens", GraphQlApi::tokenOrder))
                        .dataFetcher("revokeToken", delete(e -> "/admin/tokens/" + arg(e, "id"))))
                .type("Subscription", t -> t.dataFetcher("events", env -> {
                    Subscriptions subscriptions = env.getGraphQlContext().get(Subscriptions.class);
                    try {
                        if (subscriptions == null) {
                            throw bad("subscription-over-http",
                                    "Subscriptions run over the WebSocket " + PATH + " (graphql-transport-ws).");
                        }
                        return subscriptions.open(token(env), strings(env, "types"), strings(env, "services"));
                    } catch (ApiException e) {
                        return failed(env, e);
                    }
                }))
                .build();
        return new SchemaGenerator().makeExecutableSchema(types, wiring);
    }

    /** Fields of typed objects read the REST member of the same name in kebab case. */
    private static Object kebabField(DataFetchingEnvironment env) {
        if (env.getSource() instanceof Map<?, ?> map) {
            String name = env.getField().getName();
            Object value = map.get(name);
            return value != null || map.containsKey(name) ? value : map.get(kebab(name));
        }
        return null;
    }

    static String kebab(String camel) {
        StringBuilder out = new StringBuilder(camel.length() + 4);
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                out.append('-').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- fetchers

    @FunctionalInterface
    private interface Target {
        String of(DataFetchingEnvironment env) throws ApiException;
    }

    @FunctionalInterface
    private interface BodyOf {
        Object of(DataFetchingEnvironment env) throws ApiException;
    }

    private DataFetcher<Object> get(Target target) {
        return env -> run(env, () -> answer(call(env, "GET", target.of(env), null, null)));
    }

    private DataFetcher<Object> document(Target target) {
        return env -> run(env, () -> versioned(call(env, "GET", target.of(env), null, null)));
    }

    private DataFetcher<Object> put(Target target) {
        return env -> run(env, () -> versioned(call(env, "PUT", target.of(env), env.getArgument("document"),
                ifMatch(env))));
    }

    private DataFetcher<Object> delete(Target target) {
        return env -> run(env, () -> {
            call(env, "DELETE", target.of(env), null, ifMatch(env));
            return true;
        });
    }

    private DataFetcher<Object> call(String method, Target target, BodyOf body) {
        return env -> run(env, () -> answer(call(env, method, target.of(env), body.of(env), null)));
    }

    private DataFetcher<Object> page(String path, Function<Object, Object> items) {
        return env -> run(env, () -> {
            StringBuilder target = new StringBuilder(path);
            Integer limit = env.getArgument("limit");
            String after = env.getArgument("after");
            String separator = "?";
            if (limit != null) {
                target.append(separator).append("limit=").append(limit.intValue());
                separator = "&";
            }
            if (after != null) {
                target.append(separator).append("after=").append(checked(after, "after"));
            }
            ApiResponse response = call(env, "GET", target.toString(), null, null);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalCount", Integer.parseInt(response.headers().getOrDefault("X-Total-Count", "0")));
            String link = response.headers().get("Link");
            Matcher next = link == null ? null : NEXT.matcher(link);
            result.put("next", next != null && next.find() ? next.group(1) : null);
            result.put("items", items.apply(answer(response)));
            return result;
        });
    }

    @FunctionalInterface
    private interface Work {
        Object run() throws ApiException;
    }

    private static Object run(DataFetchingEnvironment env, Work work) {
        try {
            return work.run();
        } catch (ApiException e) {
            return failed(env, e);
        }
    }

    private static DataFetcherResult<Object> failed(DataFetchingEnvironment env, ApiException e) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("code", e.code());
        extensions.put("status", e.status());
        if (e.retryAfterSeconds() > 0) {
            extensions.put("retryAfter", e.retryAfterSeconds());
        }
        GraphQLError error = GraphqlErrorBuilder.newError(env).message(e.getMessage()).extensions(extensions).build();
        return DataFetcherResult.newResult().error(error).build();
    }

    /**
     * Runs one REST route with the caller's token, the same way {@link ApiHttpHandler} does after
     * routing: resolve, check the scope, run, map a failure to its API error.
     */
    private ApiResponse call(DataFetchingEnvironment env, String method, String target, Object body, String ifMatch)
            throws ApiException {
        ApiToken token = token(env);
        AtomicInteger calls = env.getGraphQlContext().get(AtomicInteger.class);
        int n = calls.incrementAndGet();
        if (n > MAX_CALLS) {
            throw bad("too-many-calls", "A document may run at most " + MAX_CALLS + " fields that "
                    + "read or change data.");
        }
        if (n > 1) {
            long wait = charge.tryAcquire(token.id());
            if (wait > 0) {
                throw ApiException.tooManyRequests(wait);
            }
        }
        Router.Resolution<ApiRoutes.Handler> resolution = router.resolve(method, ApiRoutes.PREFIX + target);
        if (!(resolution instanceof Router.Resolution.Found<ApiRoutes.Handler> found)) {
            throw bad("invalid-value", "An argument breaks a rule of the API.");
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

    private static Object answer(ApiResponse response) {
        if (response.body() == null) {
            return Map.of();
        }
        return ApiJson.mapper().readValue(response.body(), Object.class);
    }

    private static Map<String, Object> versioned(ApiResponse response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("etag", response.headers().get("ETag"));
        result.put("document", answer(response));
        return result;
    }

    private static Object entries(Object page) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (page instanceof Map<?, ?> map) {
            map.forEach((key, value) -> list.add(entry(String.valueOf(key), value)));
        }
        return list;
    }

    private static Object nodeEntries(Object page) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (page instanceof List<?> nodes) {
            for (Object node : nodes) {
                Object id = node instanceof Map<?, ?> m ? m.get("id") : null;
                list.add(entry(String.valueOf(id), node));
            }
        }
        return list;
    }

    private static Map<String, Object> entry(String key, Object document) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("key", key);
        entry.put("document", document);
        return entry;
    }

    private static Map<String, Object> wanted(DataFetchingEnvironment env) {
        return Map.of("wanted", env.<Boolean>getArgument("wanted"));
    }

    private static Map<String, Object> backupOrder(DataFetchingEnvironment env) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (env.getArgument("services") != null) {
            body.put("services", env.getArgument("services"));
        }
        if (env.getArgument("database") != null) {
            body.put("database", env.getArgument("database"));
        }
        if (env.getArgument("note") != null) {
            body.put("note", env.getArgument("note"));
        }
        return body;
    }

    private static Map<String, Object> tokenOrder(DataFetchingEnvironment env) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", env.getArgument("name"));
        body.put("scopes", env.getArgument("scopes"));
        if (env.getArgument("expiresIn") != null) {
            body.put("expires-in", env.getArgument("expiresIn"));
        }
        return body;
    }

    private static ApiToken token(DataFetchingEnvironment env) {
        return env.getGraphQlContext().get(ApiToken.class);
    }

    private static String ifMatch(DataFetchingEnvironment env) throws ApiException {
        String value = env.getArgument("ifMatch");
        if (value != null && (value.length() > 128 || value.chars().anyMatch(c -> c < 0x20 || c > 0x7e))) {
            throw bad("invalid-value", "\"ifMatch\" must be an ETag.");
        }
        return value;
    }

    /** An argument that becomes part of a path or query: only the characters of names and ids. */
    private static String arg(DataFetchingEnvironment env, String name) throws ApiException {
        return checked(env.getArgument(name), name);
    }

    private static String checked(Object value, String name) throws ApiException {
        if (!(value instanceof String text) || !ARGUMENT.matcher(text).matches()) {
            throw bad("invalid-value", "\"" + name + "\" must be a name or id "
                    + "(lower-case letters, digits, '.', '_', '-').");
        }
        return text;
    }

    private static List<String> strings(DataFetchingEnvironment env, String name) {
        List<String> value = env.getArgument(name);
        return value == null ? List.of() : List.copyOf(value);
    }

    // ---------------------------------------------------------------- scalars

    /** Any JSON value, passed through as maps, lists, strings, numbers and booleans. */
    static final GraphQLScalarType JSON = GraphQLScalarType.newScalar()
            .name("JSON")
            .description("Any JSON value")
            .coercing(new Coercing<Object, Object>() {
                @Override
                @SuppressWarnings("deprecation")
                public Object serialize(Object value) {
                    return value;
                }

                @Override
                @SuppressWarnings("deprecation")
                public Object parseValue(Object input) {
                    return input;
                }

                @Override
                @SuppressWarnings("deprecation")
                public Object parseLiteral(Object input) {
                    return literal((Value<?>) input, 0);
                }
            })
            .build();

    private static Object literal(Value<?> value, int depth) {
        if (depth > ApiJson.MAX_DEPTH) {
            throw new CoercingParseLiteralException("The JSON literal is nested too deep.");
        }
        return switch (value) {
            case StringValue s -> s.getValue();
            case BooleanValue b -> b.isValue();
            case IntValue i -> i.getValue();
            case FloatValue f -> f.getValue();
            case NullValue n -> null;
            case ArrayValue a -> {
                List<Object> list = new ArrayList<>();
                for (Value<?> v : a.getValues()) {
                    list.add(literal(v, depth + 1));
                }
                yield list;
            }
            case ObjectValue o -> {
                Map<String, Object> map = new LinkedHashMap<>();
                o.getObjectFields().forEach(f -> map.put(f.getName(), literal(f.getValue(), depth + 1)));
                yield map;
            }
            default -> throw new CoercingParseLiteralException("Variables are not allowed inside a JSON literal.");
        };
    }

    /** A 64-bit integer. */
    static final GraphQLScalarType LONG = GraphQLScalarType.newScalar()
            .name("Long")
            .description("A 64-bit integer")
            .coercing(new Coercing<Long, Long>() {
                @Override
                @SuppressWarnings("deprecation")
                public Long serialize(Object value) {
                    if (value instanceof Number n && !(value instanceof Double) && !(value instanceof Float)
                            && !(value instanceof BigDecimal)) {
                        return n.longValue();
                    }
                    throw new CoercingSerializeException("Not a whole number.");
                }

                @Override
                @SuppressWarnings("deprecation")
                public Long parseValue(Object input) {
                    if (input instanceof Integer || input instanceof Long || input instanceof Short) {
                        return ((Number) input).longValue();
                    }
                    if (input instanceof BigInteger b && b.bitLength() < 64) {
                        return b.longValue();
                    }
                    throw new CoercingParseValueException("Not a 64-bit integer.");
                }

                @Override
                @SuppressWarnings("deprecation")
                public Long parseLiteral(Object input) {
                    if (input instanceof IntValue i && i.getValue().bitLength() < 64) {
                        return i.getValue().longValue();
                    }
                    throw new CoercingParseLiteralException("Not a 64-bit integer.");
                }
            })
            .build();
}
