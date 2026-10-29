package net.vectora.cloud.api.http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.vectora.cloud.api.auth.ApiToken;
import net.vectora.cloud.api.auth.Scope;
import net.vectora.cloud.api.backend.ApiBackend;
import net.vectora.cloud.api.backend.BackupAccess;
import net.vectora.cloud.api.backend.BackupOrder;
import net.vectora.cloud.api.backend.ServiceView;
import net.vectora.cloud.config.ConfigChanged;
import net.vectora.cloud.config.ConfigService;
import net.vectora.cloud.config.error.ConfigError;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.model.CloudConfig;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.model.ServerSoftware;
import net.vectora.cloud.config.model.TaskConfig;
import net.vectora.cloud.config.operation.ConfigOperation;
import net.vectora.cloud.config.operation.PutGroup;
import net.vectora.cloud.config.operation.PutTask;
import net.vectora.cloud.config.operation.RemoveGroup;
import net.vectora.cloud.config.operation.RemoveTask;
import net.vectora.cloud.config.operation.SetGroupWanted;
import net.vectora.cloud.config.operation.SetServerWanted;
import net.vectora.cloud.config.validation.JvmFlagPolicy;

/**
 * The REST endpoints under {@value #PREFIX}. Every write is a {@link ConfigOperation} for
 * {@link ConfigService#apply}, so it is validated exactly like a console command or a hand-edited
 * file, including the JVM flag allowlist. The table is in {@code docs/cloud/api.md} and
 * {@code docs/cloud/api.openapi.yaml}.
 */
public final class ApiRoutes {

    /** Prefix of every endpoint. */
    public static final String PREFIX = "/api/v1";
    /** Path of the WebSocket. */
    public static final String EVENTS = PREFIX + "/events";
    /** Longest console command sent to a service. */
    public static final int MAX_COMMAND_LENGTH = 1024;
    /** Template of the legacy proxy, as {@code ServicePlanner.LEGACY_PROXY_TEMPLATE} in cloud-node. */
    static final String LEGACY_PROXY_TEMPLATE = "Legacy-Proxy";

    /** One endpoint. */
    @FunctionalInterface
    public interface Handler {

        /**
         * Answers a request.
         *
         * @param request the request
         * @return the answer
         * @throws Exception any failure; {@link ApiHttpHandler} maps it to a status
         */
        ApiResponse handle(ApiRequest request) throws Exception;
    }

    private final ConfigService config;
    private final ApiBackend backend;
    private final BooleanSupplier shuttingDown;

    /**
     * Creates the endpoints.
     *
     * @param config       the configuration layer
     * @param backend      the node
     * @param shuttingDown whether start and reload must be refused
     */
    public ApiRoutes(ConfigService config, ApiBackend backend, BooleanSupplier shuttingDown) {
        this.config = config;
        this.backend = backend;
        this.shuttingDown = shuttingDown;
    }

    /**
     * Builds the router with every endpoint.
     *
     * @return the router
     */
    public Router<Handler> router() {
        Router<Handler> r = new Router<>();
        r.add("GET", PREFIX + "/services", Scope.SERVICES_READ, q -> ApiResponse.ok(backend.services().list()));
        r.add("POST", PREFIX + "/services", Scope.SERVICES_WRITE, this::startService);
        r.add("GET", PREFIX + "/services/{id}", Scope.SERVICES_READ, q -> ApiResponse.ok(service(q.param("id"))));
        r.add("POST", PREFIX + "/services/{id}/stop", Scope.SERVICES_WRITE,
                q -> ApiResponse.ok(backend.services().stop(service(q.param("id")).id())));
        r.add("POST", PREFIX + "/services/{id}/restart", Scope.SERVICES_WRITE, q -> {
            refuseDuringShutdown();
            return ApiResponse.ok(backend.services().restart(service(q.param("id")).id()));
        });
        r.add("POST", PREFIX + "/services/{id}/command", Scope.SERVICES_WRITE, this::command);

        r.add("GET", PREFIX + "/groups", Scope.CONFIG_READ, q -> ApiResponse.ok(config.snapshot().groups()));
        r.add("GET", PREFIX + "/groups/{name}", Scope.CONFIG_READ, q -> ApiResponse.document(200, group(q.param("name"))));
        r.add("PUT", PREFIX + "/groups/{name}", Scope.CONFIG_WRITE, this::putGroup);
        r.add("DELETE", PREFIX + "/groups/{name}", Scope.CONFIG_WRITE, q -> {
            String name = q.param("name");
            group(name); // 404 for an unknown group
            confirm(q, name);
            applyIfMatch(q, s -> Optional.ofNullable(s.groups().get(name)), new RemoveGroup(name));
            return ApiResponse.noContent();
        });
        r.add("PUT", PREFIX + "/groups/{name}/wanted", Scope.CONFIG_WRITE,
                q -> {
                    group(q.param("name")); // 404 for an unknown group
                    return setGroupWanted(q.param("name"), q);
                });

        r.add("GET", PREFIX + "/tasks", Scope.CONFIG_READ, q -> ApiResponse.ok(config.snapshot().tasks()));
        r.add("GET", PREFIX + "/tasks/{name}", Scope.CONFIG_READ, q -> ApiResponse.document(200, task(q.param("name"))));
        r.add("PUT", PREFIX + "/tasks/{name}", Scope.CONFIG_WRITE, this::putTask);
        r.add("DELETE", PREFIX + "/tasks/{name}", Scope.CONFIG_WRITE, q -> {
            String name = q.param("name");
            task(name); // 404 for an unknown task
            confirm(q, name);
            applyIfMatch(q, s -> Optional.ofNullable(s.tasks().get(name)), new RemoveTask(name));
            return ApiResponse.noContent();
        });
        r.add("PUT", PREFIX + "/tasks/{name}/wanted", Scope.CONFIG_WRITE, q -> {
            TaskConfig task = task(q.param("name"));
            if (!config.snapshot().groups().containsKey(task.group())) {
                throw ApiException.notFound("The group of this task");
            }
            return setGroupWanted(task.group(), q);
        });

        r.add("GET", PREFIX + "/servers", Scope.CONFIG_READ, q -> ApiResponse.ok(servers(config.snapshot())));
        r.add("PUT", PREFIX + "/servers/{kind}", Scope.CONFIG_WRITE, q -> setWanted(kind(q.param("kind")), q));

        r.add("GET", PREFIX + "/config", Scope.CONFIG_READ, q -> ApiResponse.document(200, config.snapshot().cloud()));
        r.add("PUT", PREFIX + "/config", Scope.CONFIG_WRITE, q -> {
            CloudConfig next = q.json(CloudConfig.class);
            ConfigSnapshot after = applyIfMatch(q, s -> Optional.of(s.cloud()), snapshot -> {
                requireAdminForSensitiveSections(q.token(), snapshot.cloud(), next);
                return snapshot.withCloud(next);
            });
            return ApiResponse.document(200, after.cloud());
        });
        r.add("POST", PREFIX + "/config/reload", Scope.CONFIG_WRITE, q -> {
            refuseDuringShutdown();
            Optional<ConfigChanged> change;
            try {
                change = config.reload();
            } catch (ConfigException e) {
                throw ApiException.invalidConfig(e.errors());
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("changed", change.isPresent());
            body.put("files", change.map(c -> List.copyOf(c.files())).orElse(List.of()));
            return ApiResponse.ok(body);
        });
        r.add("GET", PREFIX + "/config/warnings", Scope.CONFIG_READ,
                q -> ApiResponse.ok(Map.of("warnings", describe(config.warnings()))));

        r.add("GET", PREFIX + "/node", Scope.NODE_READ, q -> ApiResponse.ok(backend.node().status()));
        r.add("GET", PREFIX + "/setup", Scope.CONFIG_READ,
                q -> ApiResponse.ok(backend.node().setup(config.snapshot())));

        r.add("GET", PREFIX + "/backups", Scope.BACKUPS_READ, q -> ApiResponse.ok(backups().list()));
        r.add("POST", PREFIX + "/backups", Scope.BACKUPS_WRITE, q -> {
            refuseDuringShutdown();
            BackupBody body = q.json(BackupBody.class);
            if (body.note() != null && (body.note().length() > 300 || hasControl(body.note()))) {
                throw ApiException.badRequest("invalid-value", "\"note\" must be at most 300 characters without control characters.");
            }
            if (body.services() != null) {
                for (String id : body.services()) {
                    if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
                        throw ApiException.badRequest("invalid-value", "\"services\" must list service ids.");
                    }
                }
            }
            BackupAccess access = backups();
            return ApiResponse.of(201, access.create(new BackupOrder(body.services(),
                    body.database() == null || body.database(), body.note())));
        });

        r.add("GET", PREFIX + "/tokens/self", null, q -> ApiResponse.ok(describe(q.token())));
        // The WebSocket is answered by ApiHttpHandler before routing; the route carries its scope.
        r.add("GET", EVENTS, Scope.SERVICES_READ, q -> {
            throw new ApiException(426, "upgrade-required", "This endpoint is a WebSocket.");
        });
        return r;
    }

    private ApiResponse startService(ApiRequest q) throws Exception {
        refuseDuringShutdown();
        StartBody body = q.json(StartBody.class);
        if (body.task() == null || !body.task().matches("[a-z0-9][a-z0-9_-]{0,31}")) {
            throw ApiException.badRequest("invalid-value", "\"task\" must be a task name.");
        }
        if (!config.snapshot().tasks().containsKey(body.task())) {
            throw ApiException.notFound("Task \"" + body.task() + "\"");
        }
        return ApiResponse.of(201, backend.services().start(body.task()));
    }

    private ApiResponse command(ApiRequest q) throws Exception {
        CommandBody body = q.json(CommandBody.class);
        String line = body.command();
        if (line == null || line.isEmpty() || line.length() > MAX_COMMAND_LENGTH || hasControl(line)) {
            throw ApiException.badRequest("invalid-value",
                    "\"command\" must be 1 to " + MAX_COMMAND_LENGTH + " characters without control characters.");
        }
        ServiceView target = service(q.param("id"));
        backend.services().command(target.id(), line);
        return ApiResponse.of(202, Map.of("service", target.id(), "sent", true));
    }

    private ApiResponse putGroup(ApiRequest q) throws Exception {
        String name = q.param("name");
        GroupConfig next = q.json(GroupConfig.class);
        AtomicBoolean existed = new AtomicBoolean();
        ConfigSnapshot after = applyIfMatch(q, s -> {
            Optional<GroupConfig> current = Optional.ofNullable(s.groups().get(name));
            existed.set(current.isPresent());
            return current;
        }, new PutGroup(name, next));
        return ApiResponse.document(existed.get() ? 200 : 201, after.groups().get(name));
    }

    private ApiResponse putTask(ApiRequest q) throws Exception {
        String name = q.param("name");
        TaskConfig next = q.json(TaskConfig.class);
        AtomicBoolean existed = new AtomicBoolean();
        ConfigSnapshot after = applyIfMatch(q, s -> {
            Optional<TaskConfig> current = Optional.ofNullable(s.tasks().get(name));
            existed.set(current.isPresent());
            return current;
        }, new PutTask(name, next));
        return ApiResponse.document(existed.get() ? 200 : 201, after.tasks().get(name));
    }

    private ApiResponse setWanted(ServerKind kind, ApiRequest q) throws Exception {
        apply(new SetServerWanted(kind, wanted(q)));
        return ApiResponse.ok(servers(config.snapshot()));
    }

    /**
     * Sets the group's own switch (since #44). The answer shows the group's switch, the switch of
     * its server kind and whether the group runs, by {@link #runs}.
     */
    private ApiResponse setGroupWanted(String name, ApiRequest q) throws Exception {
        boolean wanted = wanted(q);
        AtomicReference<ConfigSnapshot> result = new AtomicReference<>();
        apply(snapshot -> {
            ConfigSnapshot after = new SetGroupWanted(name, wanted).apply(snapshot);
            result.set(after);
            return after;
        });
        ConfigSnapshot snapshot = result.get();
        GroupConfig group = snapshot.groups().get(name);
        Optional<ServerKind> kind = switchOf(group);
        boolean kindWanted = kind.map(k -> k != ServerKind.DATABASE && kindSwitch(snapshot.cloud(), k)).orElse(true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("group", name);
        body.put("wanted", group.wantedOrDefault());
        body.put("server-kind", kind.map(ServerKind::key).orElse(null));
        body.put("server-kind-wanted", kindWanted);
        body.put("runs", runs(group, snapshot.cloud()));
        return ApiResponse.ok(body);
    }

    /**
     * Whether services of a group may run. The same rule as {@code ServicePlanner.isWanted(GroupConfig,
     * CloudConfig)} in cloud-node, which this module cannot see: the group's own switch, the switch of
     * its server kind ({@link #switchOf}; the database template never runs as a service), and for the
     * 1.8.8 part of the network (PandaSpigot, the {@code Legacy-Proxy} and {@code Legacy-Server}
     * templates) also {@code legacy-proxy.enabled}.
     *
     * @param group the group
     * @param cloud {@code cloud.yml}
     * @return whether it runs
     */
    static boolean runs(GroupConfig group, CloudConfig cloud) {
        boolean legacy = group.software() == ServerSoftware.PANDASPIGOT || LEGACY_PROXY_TEMPLATE.equals(group.template())
                || kindOfTemplate(group.template()).orElse(null) == ServerKind.LEGACY_SERVER;
        if (legacy && (cloud.legacyProxy() == null || !cloud.legacyProxy().enabled())) {
            return false;
        }
        if (!group.wantedOrDefault()) {
            return false;
        }
        Optional<ServerKind> kind = switchOf(group);
        return kind.isEmpty() || (kind.get() != ServerKind.DATABASE && kindSwitch(cloud, kind.get()));
    }

    /** The server kind whose switch a group follows; PandaSpigot on a template of its own is a legacy server. */
    private static Optional<ServerKind> switchOf(GroupConfig group) {
        Optional<ServerKind> kind = kindOfTemplate(group.template());
        if (kind.isEmpty() && group.software() == ServerSoftware.PANDASPIGOT) {
            return Optional.of(ServerKind.LEGACY_SERVER);
        }
        return kind;
    }

    private static boolean kindSwitch(CloudConfig cloud, ServerKind kind) {
        return cloud.servers() == null || cloud.servers().isWanted(kind);
    }

    private static boolean wanted(ApiRequest q) throws ApiException {
        WantedBody body = q.json(WantedBody.class);
        if (body.wanted() == null) {
            throw ApiException.badRequest("invalid-value", "\"wanted\" must be true or false.");
        }
        return body.wanted();
    }

    /**
     * Applies a change of the API. On top of the validation of {@link ConfigService#apply}, the change
     * may not add a JVM flag outside the allowlist ({@link JvmFlagPolicy#refusedChanges}); the check
     * runs inside the operation, against the snapshot it changes.
     */
    private void apply(ConfigOperation operation) throws ApiException, IOException {
        try {
            config.apply(snapshot -> {
                ConfigSnapshot after = operation.apply(snapshot);
                List<ConfigError> refused = JvmFlagPolicy.refusedChanges(snapshot, after);
                if (!refused.isEmpty()) {
                    throw new ConfigException(refused);
                }
                return after;
            });
        } catch (ConfigException e) {
            throw ApiException.invalidConfig(e.errors());
        }
    }

    private void refuseDuringShutdown() throws ApiException {
        if (shuttingDown.getAsBoolean() || backend.node().shuttingDown()) {
            throw ApiException.shuttingDown();
        }
    }

    private ServiceView service(String id) throws ApiException {
        return backend.services().find(id).orElseThrow(() -> ApiException.notFound("Service \"" + id + "\""));
    }

    private GroupConfig group(String name) throws ApiException {
        GroupConfig group = config.snapshot().groups().get(name);
        if (group == null) {
            throw ApiException.notFound("Group \"" + name + "\"");
        }
        return group;
    }

    private TaskConfig task(String name) throws ApiException {
        TaskConfig task = config.snapshot().tasks().get(name);
        if (task == null) {
            throw ApiException.notFound("Task \"" + name + "\"");
        }
        return task;
    }

    private BackupAccess backups() throws ApiException {
        return backend.backups().orElseThrow(() -> new ApiException(501, "not-available",
                "Backups are not available on this node."));
    }

    private static ServerKind kind(String key) throws ApiException {
        for (ServerKind kind : ServerKind.values()) {
            if (kind.key().equals(key)) {
                return kind;
            }
        }
        throw ApiException.notFound("Server kind \"" + key + "\"");
    }

    private static Optional<ServerKind> kindOfTemplate(String template) {
        for (ServerKind kind : ServerKind.values()) {
            if (kind.templateFolder().equals(template)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    private static Map<String, Boolean> servers(ConfigSnapshot snapshot) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (ServerKind kind : ServerKind.values()) {
            result.put(kind.key(), snapshot.cloud().servers() != null && snapshot.cloud().servers().isWanted(kind));
        }
        return result;
    }

    /**
     * Applies {@code operation} if {@code If-Match} fits the current document. The check runs inside
     * the operation, so under the lock of {@link ConfigService#apply}: two writers with the same
     * ETag cannot both succeed, also not against a console change in between.
     *
     * @return the snapshot the operation produced
     */
    private ConfigSnapshot applyIfMatch(ApiRequest q, Function<ConfigSnapshot, Optional<?>> document,
                                        ConfigOperation operation) throws ApiException, IOException {
        AtomicReference<ConfigSnapshot> result = new AtomicReference<>();
        try {
            apply(snapshot -> {
                ifMatch(q, document.apply(snapshot));
                ConfigSnapshot after = operation.apply(snapshot);
                result.set(after);
                return after;
            });
        } catch (Refused refused) {
            throw refused.error;
        }
        return result.get();
    }

    /** Checks {@code If-Match} against the ETag of the current document; {@code *} needs one to exist. */
    private static void ifMatch(ApiRequest q, Optional<?> current) {
        String expected = q.ifMatch();
        if (expected == null) {
            return;
        }
        boolean matches = current.isPresent()
                && (expected.equals("*") || expected.equals(ApiJson.etag(ApiJson.write(current.get()))));
        if (!matches) {
            throw new Refused(new ApiException(412, "precondition-failed",
                    "The document changed since it was read (If-Match does not match its ETag)."));
        }
    }

    /**
     * {@code api} and {@code database} of {@code cloud.yml} decide who can reach the node and which
     * image the operator's {@code docker compose} runs with the database root password; changing them
     * needs {@link Scope#ADMIN}, not only {@code config:write}.
     */
    private static void requireAdminForSensitiveSections(ApiToken token, CloudConfig current, CloudConfig next) {
        boolean sensitive = !Objects.equals(current.api(), next.api()) || !Objects.equals(current.database(), next.database());
        if (sensitive && !token.allows(Scope.ADMIN)) {
            throw new Refused(new ApiException(403, "missing-scope",
                    "Changing api or database in cloud.yml needs the scope admin."));
        }
    }

    /** Carries an {@link ApiException} out of a {@link ConfigOperation}, which may only throw ConfigException. */
    private static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient ApiException error;

        Refused(ApiException error) {
            super(error.getMessage(), null, false, false);
            this.error = error;
        }
    }

    private static void confirm(ApiRequest q, String name) throws ApiException {
        if (!q.query("confirm").map(name::equals).orElse(false)) {
            throw ApiException.badRequest("confirmation-required",
                    "Deleting needs the name repeated as ?confirm=<name>.");
        }
    }

    private static boolean hasControl(String text) {
        return text.chars().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029);
    }

    private static List<Map<String, Object>> describe(List<ConfigError> errors) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ConfigError e : errors) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("file", e.file());
            item.put("path", e.path());
            item.put("code", e.code().code());
            item.put("message", e.message(Language.EN));
            item.put("message-de", e.message(Language.DE));
            list.add(item);
        }
        return list;
    }

    private static Map<String, Object> describe(ApiToken token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", token.id());
        body.put("name", token.name());
        body.put("scopes", token.scopes().stream().map(Scope::id).sorted().toList());
        body.put("created-at", token.createdAt().toString());
        return body;
    }

    /** Body of {@code POST /services}. */
    record StartBody(String task) {
    }

    /** Body of {@code POST /services/{id}/command}. */
    record CommandBody(String command) {
    }

    /** Body of the {@code wanted} endpoints. */
    record WantedBody(Boolean wanted) {
    }

    /** Body of {@code POST /backups}. */
    record BackupBody(List<String> services, Boolean database, String note) {
    }
}
