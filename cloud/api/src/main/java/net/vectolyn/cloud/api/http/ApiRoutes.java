// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.vectolyn.cloud.api.auth.ApiToken;
import net.vectolyn.cloud.api.auth.ApiTokens;
import net.vectolyn.cloud.api.auth.Scope;
import net.vectolyn.cloud.api.backend.ApiBackend;
import net.vectolyn.cloud.api.backend.BackupAccess;
import net.vectolyn.cloud.api.backend.BackupOrder;
import net.vectolyn.cloud.api.backend.BackupView;
import net.vectolyn.cloud.api.backend.ServiceView;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.ConfigChanged;
import net.vectolyn.cloud.config.ConfigService;
import net.vectolyn.cloud.config.error.ConfigError;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.io.ConfigFormat;
import net.vectolyn.cloud.config.model.CloudConfig;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.GroupConfig;
import net.vectolyn.cloud.config.model.GroupRules;
import net.vectolyn.cloud.config.model.Language;
import net.vectolyn.cloud.config.model.NodeConfig;
import net.vectolyn.cloud.config.model.ServerKind;
import net.vectolyn.cloud.config.model.TaskConfig;
import net.vectolyn.cloud.config.operation.ConfigOperation;
import net.vectolyn.cloud.config.operation.PutGroup;
import net.vectolyn.cloud.config.operation.PutNode;
import net.vectolyn.cloud.config.operation.PutTask;
import net.vectolyn.cloud.config.operation.RemoveGroup;
import net.vectolyn.cloud.config.operation.RemoveTask;
import net.vectolyn.cloud.config.operation.SetGroupWanted;
import net.vectolyn.cloud.config.operation.SetServerWanted;
import net.vectolyn.cloud.config.template.TemplateFiles;
import net.vectolyn.cloud.config.validation.ConfigNames;
import net.vectolyn.cloud.config.validation.JvmFlagPolicy;

/**
 * The REST endpoints under {@value #PREFIX}. Every write is a {@link ConfigOperation} for
 * {@link ConfigService#apply}, so it is validated exactly like a console command or a hand-edited
 * file, including the JVM flag allowlist. The table is in {@code docs/cloud/api.md} and
 * {@code docs/cloud/api.openapi.yaml}.
 */
public final class ApiRoutes {

    /** Prefix of every endpoint. */
    public static final String PREFIX = "/api/v1";
    /**
     * Exchanges the one-time setup token for the first administrator's API token. Only the setup
     * token itself is accepted as bearer here ({@code ApiHttpHandler}); an API token gets 403.
     */
    public static final String SETUP_CLAIM = PREFIX + "/setup/claim";
    /** Path of the WebSocket. */
    public static final String EVENTS = PREFIX + "/events";
    /** Longest console command sent to a service. */
    public static final int MAX_COMMAND_LENGTH = 1024;

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
    private final ApiTokens tokens;
    private final TemplateFiles templateFiles;

    /**
     * Creates the endpoints.
     *
     * @param config       the configuration layer
     * @param backend      the node
     * @param shuttingDown whether start and reload must be refused
     */
    public ApiRoutes(ConfigService config, ApiBackend backend, BooleanSupplier shuttingDown) {
        this(config, backend, shuttingDown, null);
    }

    /**
     * Creates the endpoints with the token store, for the admin endpoints.
     *
     * @param config       the configuration layer
     * @param backend      the node
     * @param shuttingDown whether start and reload must be refused
     * @param tokens       the token store, or null if the admin endpoints answer 501
     */
    public ApiRoutes(ConfigService config, ApiBackend backend, BooleanSupplier shuttingDown, ApiTokens tokens) {
        this.config = config;
        this.backend = backend;
        this.shuttingDown = shuttingDown;
        this.tokens = tokens;
        this.templateFiles = new TemplateFiles(config.home());
    }

    /**
     * Builds the router with every endpoint.
     *
     * @return the router
     */
    public Router<Handler> router() {
        Router<Handler> r = new Router<>();
        r.add("GET", PREFIX + "/services", Scope.SERVICES_READ,
                q -> Page.list(q, PREFIX + "/services", backend.services().list(), ServiceView::id));
        r.add("POST", PREFIX + "/services", Scope.SERVICES_WRITE, this::startService);
        r.add("GET", PREFIX + "/services/{id}", Scope.SERVICES_READ, q -> ApiResponse.ok(service(q.param("id"))));
        r.add("POST", PREFIX + "/services/{id}/stop", Scope.SERVICES_WRITE,
                q -> ApiResponse.ok(backend.services().stop(service(q.param("id")).id())));
        r.add("POST", PREFIX + "/services/{id}/restart", Scope.SERVICES_WRITE, q -> {
            refuseDuringShutdown();
            return ApiResponse.ok(backend.services().restart(service(q.param("id")).id()));
        });
        r.add("POST", PREFIX + "/services/{id}/command", Scope.SERVICES_WRITE, this::command);

        r.add("GET", PREFIX + "/groups", Scope.CONFIG_READ, q -> Page.map(q, PREFIX + "/groups", config.snapshot().groups()));
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

        r.add("GET", PREFIX + "/tasks", Scope.CONFIG_READ, q -> Page.map(q, PREFIX + "/tasks", config.snapshot().tasks()));
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

        r.add("GET", PREFIX + "/nodes", Scope.CONFIG_READ,
                q -> Page.list(q, PREFIX + "/nodes", config.snapshot().nodes().nodes(), NodeConfig::id));
        r.add("GET", PREFIX + "/nodes/{name}", Scope.CONFIG_READ, q -> ApiResponse.document(200, node(q.param("name"))));
        r.add("PUT", PREFIX + "/nodes/{name}", Scope.CONFIG_WRITE, this::putNode);

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

        r.add("GET", PREFIX + "/backups", Scope.BACKUPS_READ,
                q -> Page.list(q, PREFIX + "/backups", backups().list(), BackupView::id));
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

        r.add("GET", PREFIX + "/templates", Scope.TEMPLATES_READ, q -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (String name : templates().templates()) {
                list.add(Map.of("name", name));
            }
            return ApiResponse.ok(list);
        });
        r.add("GET", PREFIX + "/templates/{template}/files", Scope.TEMPLATES_READ, this::templateFiles);
        r.add("GET", PREFIX + "/templates/{template}/files/{path}", Scope.TEMPLATES_READ, this::templateFile);
        r.add("PUT", PREFIX + "/templates/{template}/files/{path}", Scope.TEMPLATES_WRITE, this::putTemplateFile);

        r.add("GET", PREFIX + "/tokens/self", null, q -> ApiResponse.ok(describe(q.token(), now())));

        r.add("GET", PREFIX + "/admin/tokens", Scope.ADMIN, q -> {
            Instant now = tokens().clock().instant();
            return Page.list(q, PREFIX + "/admin/tokens",
                    tokens().list().stream().map(t -> describe(t, now)).toList(), t -> (String) t.get("id"));
        });
        r.add("POST", PREFIX + "/admin/tokens", Scope.ADMIN, this::createToken);
        r.add("DELETE", PREFIX + "/admin/tokens/{id}", Scope.ADMIN, q -> {
            if (tokens().revoke(q.param("id")).isEmpty()) {
                throw ApiException.notFound("Token \"" + q.param("id") + "\"");
            }
            return ApiResponse.noContent();
        });
        // Reached with an API token only; the setup token is answered by ApiHttpHandler before routing.
        r.add("POST", SETUP_CLAIM, null, q -> {
            throw new ApiException(403, "setup-token-required",
                    "This endpoint takes the one-time setup token (vcs_...), not an API token.");
        });
        // The WebSocket is answered by ApiHttpHandler before routing. It checks the scope per event
        // type (ApiEvent.TYPES) on the handshake, so the route itself needs none.
        r.add("GET", EVENTS, null, q -> {
            throw new ApiException(426, "upgrade-required", "This endpoint is a WebSocket.");
        });
        return r;
    }

    /**
     * {@code POST /admin/tokens}: the one answer of the API that holds a token. It is shown once,
     * like at the console; the node keeps only its hash.
     */
    private ApiResponse createToken(ApiRequest q) throws Exception {
        CreateTokenBody body = q.json(CreateTokenBody.class);
        if (body.name() == null || !ConfigNames.isValidName(body.name())) {
            throw ApiException.badRequest("invalid-value", "\"name\" must match " + ConfigNames.NAME + ".");
        }
        if (body.scopes() == null || body.scopes().isEmpty() || body.scopes().size() > Scope.values().length) {
            throw ApiException.badRequest("invalid-value", "\"scopes\" must list at least one scope.");
        }
        Set<Scope> scopes = EnumSet.noneOf(Scope.class);
        for (String id : body.scopes()) {
            Scope scope = Scope.of(id).orElseThrow(() -> ApiException.badRequest("invalid-value",
                    "\"scopes\" has an unknown scope."));
            scopes.add(scope);
        }
        Duration lifetime = null;
        if (body.expiresIn() != null) {
            try {
                lifetime = ApiTokens.parseLifetime(body.expiresIn());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("invalid-value",
                        "\"expires-in\" must be 1h to 3650d, written " + ApiTokens.LIFETIME_FORMAT + ".");
            }
        }
        ApiTokens store = tokens();
        if (store.list().stream().anyMatch(t -> t.name().equals(body.name()))) {
            throw new ApiException(409, "conflict", "A token with this name exists.");
        }
        if (store.list().size() >= ApiTokens.MAX_TOKENS) {
            throw new ApiException(409, "conflict", "At most " + ApiTokens.MAX_TOKENS + " tokens are possible.");
        }
        ApiTokens.Created created;
        try {
            created = store.create(body.name(), scopes, lifetime);
        } catch (IllegalArgumentException e) {
            throw new ApiException(409, "conflict", "The token could not be created (name taken or limit reached).");
        }
        Map<String, Object> answer = describe(created.info(), store.clock().instant());
        answer.put("token", created.token().reveal());
        return ApiResponse.of(201, answer);
    }

    private Instant now() {
        return tokens == null ? Instant.now() : tokens.clock().instant();
    }

    private ApiTokens tokens() throws ApiException {
        if (tokens == null) {
            throw new ApiException(501, "not-available", "Token management is not available on this server.");
        }
        return tokens;
    }

    /** {@code GET /templates/{template}/files}: every file the API hands out, by path. */
    private ApiResponse templateFiles(ApiRequest q) throws ApiException, IOException {
        String template = q.param("template");
        TemplateFiles.Listing listing = templateCall(template, null, () -> templates().files(template));
        List<Map<String, Object>> files = new ArrayList<>();
        for (TemplateFiles.FileInfo file : listing.files()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", file.path());
            item.put("size", file.size());
            files.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("template", template);
        body.put("files", files);
        body.put("truncated", listing.truncated());
        return ApiResponse.ok(body);
    }

    /**
     * {@code GET /templates/{template}/files/{path}}: one file, as UTF-8 text when it is valid
     * UTF-8 without NUL, otherwise as Base64. The {@code ETag} is that of the file's bytes, for
     * {@code If-Match} on the {@code PUT}.
     */
    private ApiResponse templateFile(ApiRequest q) throws ApiException, IOException {
        String template = q.param("template");
        String path = q.param("path");
        byte[] content = templateCall(template, path, () -> templates().file(template, path));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("template", template);
        body.put("path", path);
        body.put("size", content.length);
        String text = utf8(content);
        body.put("encoding", text != null ? "utf-8" : "base64");
        body.put("content", text != null ? text : Base64.getEncoder().encodeToString(content));
        return new ApiResponse(200, ApiJson.write(body), Map.of("ETag", TemplateFiles.etag(content)));
    }

    /**
     * {@code PUT /templates/{template}/files/{path}}: creates a file (without {@code If-Match}) or
     * replaces one ({@code If-Match} with its ETag or {@code *}). Refused on a cluster member, whose
     * templates come from the head.
     */
    private ApiResponse putTemplateFile(ApiRequest q) throws ApiException, IOException {
        if (config.mirrorMode()) {
            throw new ApiException(409, "managed-by-head",
                    "This node is a cluster member; its templates come from the head. Change them there.");
        }
        String template = q.param("template");
        String path = q.param("path");
        TemplateFileBody body = q.json(TemplateFileBody.class);
        if (body.content() == null) {
            throw ApiException.badRequest("invalid-value", "\"content\" is missing.");
        }
        byte[] content;
        String encoding = body.encoding() == null ? "utf-8" : body.encoding();
        if (encoding.equals("utf-8")) {
            content = body.content().getBytes(StandardCharsets.UTF_8);
        } else if (encoding.equals("base64")) {
            try {
                content = Base64.getDecoder().decode(body.content());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("invalid-value", "\"content\" is not valid Base64.");
            }
        } else {
            throw ApiException.badRequest("invalid-value", "\"encoding\" must be utf-8 or base64.");
        }
        TemplateFiles.Outcome outcome = templateCall(template, path,
                () -> templates().write(template, path, content, q.ifMatch()));
        String etag = TemplateFiles.etag(content);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("template", template);
        answer.put("path", path);
        answer.put("size", content.length);
        answer.put("etag", etag);
        return new ApiResponse(outcome == TemplateFiles.Outcome.CREATED ? 201 : 200, ApiJson.write(answer),
                Map.of("ETag", etag));
    }

    /** One call of {@link TemplateFiles}; maps its failures to answers. */
    private static <T> T templateCall(String template, String path, TemplateCall<T> call)
            throws ApiException, IOException {
        try {
            return call.run();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid-path", path == null
                    ? "This is no template name."
                    : "This path is not handed out: it breaks the path rules or looks like a secret.");
        } catch (NoSuchFileException e) {
            throw ApiException.notFound(path == null ? "Template" : "File");
        } catch (TemplateFiles.TooLargeException e) {
            throw new ApiException(413, "file-too-large", "The file is larger than " + e.limit() + " bytes.");
        } catch (TemplateFiles.PreconditionException e) {
            throw e.required() ? new ApiException(428, "precondition-required", e.getMessage())
                    : new ApiException(412, "precondition-failed", e.getMessage());
        } catch (FileSystemException e) {
            throw new ApiException(409, "not-a-regular-file",
                    "A link, junction, special file or file is in the way of this path.");
        }
    }

    /** A call of {@link TemplateFiles}. */
    @FunctionalInterface
    private interface TemplateCall<T> {
        T run() throws IOException, TemplateFiles.PreconditionException;
    }

    /** The text of a file if it is valid UTF-8 without NUL, else null. */
    private static String utf8(byte[] content) {
        for (byte b : content) {
            if (b == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private TemplateFiles templates() {
        return templateFiles;
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
        GroupConfig next = ApiJson.readDocument(q.body(), CloudHome.groupFileName(ConfigNames.safeArgument(name)),
                ConfigFormat.GROUP);
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
        // A body of schema-version 1 (with mode) runs through the migration of a task file.
        TaskConfig next = ApiJson.readDocument(q.body(), CloudHome.taskFileName(ConfigNames.safeArgument(name)),
                ConfigFormat.TASK);
        AtomicBoolean existed = new AtomicBoolean();
        ConfigSnapshot after = applyIfMatch(q, s -> {
            Optional<TaskConfig> current = Optional.ofNullable(s.tasks().get(name));
            existed.set(current.isPresent());
            return current;
        }, new PutTask(name, next));
        return ApiResponse.document(existed.get() ? 200 : 201, after.tasks().get(name));
    }

    /**
     * Creates or replaces one node of {@code nodes.yml}. The body is the whole node; its {@code id}
     * may be left out and must otherwise be the id of the path.
     */
    private ApiResponse putNode(ApiRequest q) throws Exception {
        String id = q.param("name");
        NodeConfig body = q.json(NodeConfig.class);
        if (body.id() != null && !body.id().equals(id)) {
            throw ApiException.badRequest("invalid-value", "\"id\" must be left out or match the path.");
        }
        NodeConfig next = new NodeConfig(id, body.host(), body.port(), body.servicePorts(), body.maxMemory(), body.reserve());
        AtomicBoolean existed = new AtomicBoolean();
        ConfigSnapshot after = applyIfMatch(q, s -> {
            Optional<NodeConfig> current = s.nodes().find(id);
            existed.set(current.isPresent());
            return current;
        }, new PutNode(next));
        return ApiResponse.document(existed.get() ? 200 : 201, after.nodes().find(id).orElseThrow());
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
     * Whether services of a group may run: {@link GroupRules#isWanted(GroupConfig, CloudConfig)},
     * the very rule the node plans and starts by.
     *
     * @param group the group
     * @param cloud {@code cloud.yml}
     * @return whether it runs
     */
    static boolean runs(GroupConfig group, CloudConfig cloud) {
        return GroupRules.isWanted(group, cloud);
    }

    private static Optional<ServerKind> switchOf(GroupConfig group) {
        return GroupRules.switchOf(group);
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

    private NodeConfig node(String id) throws ApiException {
        return config.snapshot().nodes().find(id).orElseThrow(() -> ApiException.notFound("Node \"" + id + "\""));
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

    /**
     * The answer to a successful {@value #SETUP_CLAIM}: the new API token with its description. The
     * only place the token leaves the node.
     *
     * @param created the new token
     * @param now     the time its expiry is judged against
     * @return 201 with id, name, scopes, created-at and token
     */
    public static ApiResponse claimed(net.vectolyn.cloud.api.auth.ApiTokens.Created created, Instant now) {
        Map<String, Object> body = describe(created.info(), now);
        body.put("token", created.token().reveal());
        return ApiResponse.of(201, body);
    }

    private static Map<String, Object> describe(ApiToken token, Instant now) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", token.id());
        body.put("name", token.name());
        body.put("scopes", token.scopes().stream().map(Scope::id).sorted().toList());
        body.put("created-at", token.createdAt().toString());
        if (token.expiresAt() != null) {
            body.put("expires-at", token.expiresAt().toString());
        }
        body.put("expired", token.isExpired(now));
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

    /** Body of {@code POST /admin/tokens}. */
    record CreateTokenBody(String name, List<String> scopes, String expiresIn) {
    }

    /** Body of {@code PUT /templates/{template}/files/{path}}. */
    record TemplateFileBody(String content, String encoding) {
    }

    /** Body of {@code POST /backups}. */
    record BackupBody(List<String> services, Boolean database, String note) {
    }
}
