// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.vectolyn.cloud.api.auth.ApiToken;
import net.vectolyn.cloud.api.http.ApiException;
import net.vectolyn.cloud.api.http.ApiJson;
import net.vectolyn.cloud.api.http.ApiResponse;

/**
 * The unary methods of {@code vectolyn.api.v1.CloudService}. Each one builds the REST request
 * named in {@code cloud.proto} and runs it with {@link RouteCaller}, so scope, validation and
 * errors are those of REST. Requests and answers are maps keyed by proto field names
 * ({@link net.vectolyn.cloud.api.grpc.Proto}); REST members in kebab case become snake case.
 */
final class GrpcCalls {

    private static final Pattern NEXT = Pattern.compile("[?&]after=([^&>]+)");

    private final RouteCaller routes;

    GrpcCalls(RouteCaller routes) {
        this.routes = routes;
    }

    /** One unary method. */
    @FunctionalInterface
    interface Call {
        Map<String, Object> run(ApiToken token, Map<String, Object> request) throws ApiException;
    }

    /**
     * The unary methods by name; {@code Events} is served by the stream handler.
     *
     * @return the table
     */
    Map<String, Call> table() {
        Map<String, Call> t = new LinkedHashMap<>();
        t.put("ListServices", (k, q) -> page(k, "/services", q, GrpcCalls::snakeItems));
        t.put("GetService", (k, q) -> object(routes.call(k, "GET", "/services/" + seg(q, "id"), null, null)));
        t.put("StartService", (k, q) -> object(routes.call(k, "POST", "/services", Map.of("task", text(q, "task")), null)));
        t.put("StopService", (k, q) -> object(routes.call(k, "POST", "/services/" + seg(q, "id") + "/stop", null, null)));
        t.put("RestartService", (k, q) -> object(routes.call(k, "POST", "/services/" + seg(q, "id") + "/restart", null,
                null)));
        t.put("SendCommand", (k, q) -> json(routes.call(k, "POST", "/services/" + seg(q, "id") + "/command",
                Map.of("command", text(q, "command")), null)));
        t.put("ListDocuments", (k, q) -> {
            String kind = kind(q);
            return switch (kind) {
                case "DOCUMENT_KIND_GROUP" -> page(k, "/groups", q, GrpcCalls::entries);
                case "DOCUMENT_KIND_TASK" -> page(k, "/tasks", q, GrpcCalls::entries);
                case "DOCUMENT_KIND_NODE" -> page(k, "/nodes", q, GrpcCalls::nodeEntries);
                default -> throw invalid("\"kind\" must be GROUP, TASK or NODE.");
            };
        });
        t.put("GetDocument", (k, q) -> document(routes.call(k, "GET", documentPath(q), null, null)));
        t.put("PutDocument", (k, q) -> {
            Object body;
            try {
                body = ApiJson.mapper().readValue(text(q, "json"), Object.class);
            } catch (RuntimeException e) {
                throw invalid("\"json\" is not valid JSON.");
            }
            return document(routes.call(k, "PUT", documentPath(q), body, RouteCaller.ifMatch(text(q, "if_match"))));
        });
        t.put("DeleteDocument", (k, q) -> {
            String kind = kind(q);
            if (!kind.equals("DOCUMENT_KIND_GROUP") && !kind.equals("DOCUMENT_KIND_TASK")) {
                throw invalid("\"kind\" must be GROUP or TASK.");
            }
            routes.call(k, "DELETE", documentPath(q) + "?confirm=" + RouteCaller.segment(text(q, "confirm"),
                    "confirm"), null, RouteCaller.ifMatch(text(q, "if_match")));
            return Map.of();
        });
        t.put("SetWanted", (k, q) -> {
            Map<String, Object> body = Map.of("wanted", Boolean.TRUE.equals(q.get("wanted")));
            String target = switch (kind(q)) {
                case "DOCUMENT_KIND_GROUP" -> "/groups/" + seg(q, "name") + "/wanted";
                case "DOCUMENT_KIND_TASK" -> "/tasks/" + seg(q, "name") + "/wanted";
                case "DOCUMENT_KIND_SERVER" -> "/servers/" + seg(q, "name");
                default -> throw invalid("\"kind\" must be GROUP, TASK or SERVER.");
            };
            return json(routes.call(k, "PUT", target, body, null));
        });
        t.put("GetServers", (k, q) -> json(routes.call(k, "GET", "/servers", null, null)));
        t.put("GetConfigWarnings", (k, q) -> json(routes.call(k, "GET", "/config/warnings", null, null)));
        t.put("ReloadConfig", (k, q) -> json(routes.call(k, "POST", "/config/reload", null, null)));
        t.put("GetNodeStatus", (k, q) -> object(routes.call(k, "GET", "/node", null, null)));
        t.put("GetSetup", (k, q) -> object(routes.call(k, "GET", "/setup", null, null)));
        t.put("ListBackups", (k, q) -> page(k, "/backups", q, GrpcCalls::snakeItems));
        t.put("CreateBackup", (k, q) -> {
            Map<String, Object> body = new LinkedHashMap<>();
            List<?> services = q.get("services") instanceof List<?> l ? l : List.of();
            if (!services.isEmpty()) {
                body.put("services", services);
            } else if (Boolean.TRUE.equals(q.get("only_database"))) {
                body.put("services", List.of());
            }
            if (q.get("database") instanceof Boolean database) {
                body.put("database", database);
            }
            if (q.get("note") instanceof String note) {
                body.put("note", note);
            }
            return object(routes.call(k, "POST", "/backups", body, null));
        });
        t.put("GetSelf", (k, q) -> object(routes.call(k, "GET", "/tokens/self", null, null)));
        t.put("ListTokens", (k, q) -> page(k, "/admin/tokens", q, GrpcCalls::snakeItems));
        t.put("CreateToken", (k, q) -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", text(q, "name"));
            body.put("scopes", q.get("scopes") instanceof List<?> l ? l : List.of());
            if (!text(q, "expires_in").isEmpty()) {
                body.put("expires-in", text(q, "expires_in"));
            }
            Map<String, Object> created = object(routes.call(k, "POST", "/admin/tokens", body, null));
            Object secret = created.remove("token");
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("token_info", created);
            answer.put("token", secret);
            return answer;
        });
        t.put("RevokeToken", (k, q) -> {
            routes.call(k, "DELETE", "/admin/tokens/" + seg(q, "id"), null, null);
            return Map.of();
        });
        return t;
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> page(ApiToken token, String path, Map<String, Object> q,
                                     java.util.function.Function<Object, List<Object>> items) throws ApiException {
        StringBuilder target = new StringBuilder(path);
        String separator = "?";
        if (q.get("limit") instanceof Integer limit && limit != 0) {
            target.append(separator).append("limit=").append(limit.intValue());
            separator = "&";
        }
        String after = text(q, "after");
        if (!after.isEmpty()) {
            target.append(separator).append("after=").append(RouteCaller.segment(after, "after"));
        }
        ApiResponse response = routes.call(token, "GET", target.toString(), null, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_count", Integer.parseInt(response.headers().getOrDefault("X-Total-Count", "0")));
        String link = response.headers().get("Link");
        Matcher next = link == null ? null : NEXT.matcher(link);
        result.put("next", next != null && next.find() ? next.group(1) : "");
        result.put("items", items.apply(RouteCaller.json(response)));
        return result;
    }

    private static List<Object> snakeItems(Object list) {
        List<Object> result = new ArrayList<>();
        if (list instanceof List<?> items) {
            for (Object item : items) {
                result.add(snake(item));
            }
        }
        return result;
    }

    private static List<Object> entries(Object page) {
        List<Object> list = new ArrayList<>();
        if (page instanceof Map<?, ?> map) {
            map.forEach((key, value) -> list.add(entry(String.valueOf(key), value)));
        }
        return list;
    }

    private static List<Object> nodeEntries(Object page) {
        List<Object> list = new ArrayList<>();
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
        entry.put("json", write(document));
        return entry;
    }

    private static String documentPath(Map<String, Object> q) throws ApiException {
        return switch (kind(q)) {
            case "DOCUMENT_KIND_GROUP" -> "/groups/" + seg(q, "name");
            case "DOCUMENT_KIND_TASK" -> "/tasks/" + seg(q, "name");
            case "DOCUMENT_KIND_NODE" -> "/nodes/" + seg(q, "name");
            case "DOCUMENT_KIND_CONFIG" -> "/config";
            default -> throw invalid("\"kind\" must be GROUP, TASK, NODE or CONFIG.");
        };
    }

    private static Map<String, Object> document(ApiResponse response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("etag", response.headers().getOrDefault("ETag", ""));
        result.put("json", write(RouteCaller.json(response)));
        return result;
    }

    private static Map<String, Object> json(ApiResponse response) {
        return Map.of("json", write(RouteCaller.json(response)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(ApiResponse response) {
        Object value = snake(RouteCaller.json(response));
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    /** REST members in kebab case become proto names in snake case, recursively. */
    static Object snake(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, v) -> result.put(String.valueOf(key).replace('-', '_'), snake(v)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(v -> result.add(snake(v)));
            return result;
        }
        return value;
    }

    static String write(Object value) {
        return new String(ApiJson.write(value), StandardCharsets.UTF_8);
    }

    private static String kind(Map<String, Object> q) {
        Object kind = q.get("kind");
        return kind instanceof String s ? s : "DOCUMENT_KIND_UNSPECIFIED";
    }

    private static String seg(Map<String, Object> q, String name) throws ApiException {
        return RouteCaller.segment(q.get(name), name);
    }

    private static String text(Map<String, Object> q, String name) {
        return q.get(name) instanceof String s ? s : "";
    }

    private static ApiException invalid(String message) {
        return new ApiException(400, "invalid-value", message);
    }
}
