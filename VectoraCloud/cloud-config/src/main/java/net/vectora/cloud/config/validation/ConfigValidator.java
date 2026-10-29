package net.vectora.cloud.config.validation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.error.ConfigError;
import net.vectora.cloud.config.error.ErrorCode;
import net.vectora.cloud.config.model.ApiConfig;
import net.vectora.cloud.config.model.CatalogKind;
import net.vectora.cloud.config.model.CatalogReference;
import net.vectora.cloud.config.model.CloudConfig;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.HeapConfig;
import net.vectora.cloud.config.model.NodeConfig;
import net.vectora.cloud.config.model.NodesConfig;
import net.vectora.cloud.config.model.ProcessConfig;
import net.vectora.cloud.config.model.ProxyConfig;
import net.vectora.cloud.config.model.RestartConfig;
import net.vectora.cloud.config.model.ScalingConfig;
import net.vectora.cloud.config.model.ServerSoftware;
import net.vectora.cloud.config.model.TaskConfig;

/**
 * Checks a whole {@link ConfigSnapshot}: value ranges, patterns, references between files and port
 * conflicts on this node. Returns every problem, not only the first.
 *
 * <p>Errors never contain the checked value, only the limit or pattern it broke.
 */
public final class ConfigValidator {

    /** Lowest port. */
    public static final int MIN_PORT = 1;
    /** Highest port. */
    public static final int MAX_PORT = 65535;
    /** Upper limit for services of one task. */
    public static final int MAX_SERVICES = 1000;
    /** Upper limit for players per service. */
    public static final int MAX_PLAYERS_PER_SERVICE = 10_000;
    /** Only Minecraft version PandaSpigot runs. */
    public static final String PANDASPIGOT_VERSION = "1.8.8";

    private ConfigValidator() {
    }

    /** All problems of {@code snapshot}; empty when it is valid. */
    public static List<ConfigError> validate(ConfigSnapshot snapshot) {
        Checks checks = new Checks();
        validateCloud(checks, snapshot.cloud());
        validateNodes(checks, snapshot.nodes());
        snapshot.groups().forEach((name, group) -> validateGroup(checks, name, group));
        snapshot.tasks().forEach((name, task) -> validateTask(checks, name, task, snapshot));
        validateReferences(checks, snapshot);
        validatePorts(checks, snapshot);
        return checks.errors();
    }

    private static void validateCloud(Checks c, CloudConfig cloud) {
        String f = CloudHome.CLOUD_FILE;
        c.version(f, cloud.schemaVersion(), CloudConfig.SCHEMA_VERSION);
        c.pattern(f, "network-name", cloud.networkName(), ConfigNames.NETWORK_NAME);
        c.required(f, "language", cloud.language());
        c.name(f, "node-id", cloud.nodeId());
        c.required(f, "servers", cloud.servers());
        if (cloud.setupComplete() && !cloud.eulaAccepted()) {
            c.add(ConfigError.of(f, "setup-complete", ErrorCode.EULA_REQUIRED));
        }
        ProxyConfig proxy = cloud.proxy();
        if (c.required(f, "proxy", proxy)) {
            c.pattern(f, "proxy.bind", proxy.bind(), ConfigNames.HOST);
            c.port(f, "proxy.port", proxy.port());
            c.port(f, "proxy.bedrock-port", proxy.bedrockPort());
        }
        if (c.required(f, "legacy-proxy", cloud.legacyProxy())) {
            c.port(f, "legacy-proxy.port", cloud.legacyProxy().port());
        }
        validateDatabase(c, f, cloud.database());
        validateApi(c, f, cloud.api());
    }

    private static void validateDatabase(Checks c, String f, DatabaseConfig db) {
        if (!c.required(f, "database", db)) {
            return;
        }
        c.required(f, "database.mode", db.mode());
        c.pattern(f, "database.host", db.host(), ConfigNames.HOST);
        c.port(f, "database.port", db.port());
        c.pattern(f, "database.name", db.name(), ConfigNames.DATABASE_IDENTIFIER);
        c.pattern(f, "database.user", db.user(), ConfigNames.DATABASE_IDENTIFIER);
        if (db.mode() == DatabaseMode.DOCKER) {
            c.pattern(f, "database.docker-image", db.dockerImage(), ConfigNames.DOCKER_IMAGE);
        } else if (db.dockerImage() != null) {
            c.add(ConfigError.of(f, "database.docker-image", ErrorCode.NOT_ALLOWED, "database.mode != docker"));
        }
    }

    private static void validateApi(Checks c, String f, ApiConfig api) {
        if (!c.required(f, "api", api)) {
            return;
        }
        c.pattern(f, "api.bind", api.bind(), ConfigNames.HOST);
        c.port(f, "api.port", api.port());
        List<String> origins = api.corsOrigins();
        c.range(f, "api.cors-origins", origins.size(), 0, ApiConfig.MAX_CORS_ORIGINS);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < origins.size() && i < ApiConfig.MAX_CORS_ORIGINS; i++) {
            String p = "api.cors-origins[" + i + "]";
            String origin = origins.get(i);
            if (origin == null || !ConfigNames.isOrigin(origin)) {
                c.add(ConfigError.of(f, p, ErrorCode.INVALID_ORIGIN));
            } else if (!seen.add(origin)) {
                c.add(ConfigError.of(f, p, ErrorCode.DUPLICATE_ENTRY, origin));
            }
        }
        if (!c.required(f, "api.tls", api.tls())) {
            return;
        }
        if (api.tls().enabled()) {
            c.pattern(f, "api.tls.keystore", api.tls().keystore(), ConfigNames.RELATIVE_PATH);
        } else if (api.enabled() && api.bind() != null && !ConfigNames.isLoopback(api.bind())) {
            c.add(ConfigError.of(f, "api.tls.enabled", ErrorCode.API_TLS_REQUIRED));
        }
    }

    private static void validateNodes(Checks c, NodesConfig nodes) {
        String f = CloudHome.NODES_FILE;
        c.version(f, nodes.schemaVersion(), NodesConfig.SCHEMA_VERSION);
        if (nodes.nodes().isEmpty()) {
            c.add(ConfigError.of(f, "nodes", ErrorCode.EMPTY));
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < nodes.nodes().size(); i++) {
            NodeConfig node = nodes.nodes().get(i);
            String p = "nodes[" + i + "]";
            if (node == null) {
                c.add(ConfigError.of(f, p, ErrorCode.REQUIRED));
                continue;
            }
            if (c.name(f, p + ".id", node.id()) && !ids.add(node.id())) {
                c.add(ConfigError.of(f, p + ".id", ErrorCode.DUPLICATE_ENTRY, node.id()));
            }
            c.pattern(f, p + ".host", node.host(), ConfigNames.HOST);
            c.port(f, p + ".port", node.port());
            if (c.required(f, p + ".service-ports", node.servicePorts())) {
                boolean first = c.port(f, p + ".service-ports.first", node.servicePorts().first());
                boolean last = c.port(f, p + ".service-ports.last", node.servicePorts().last());
                if (first && last && node.servicePorts().first() > node.servicePorts().last()) {
                    c.add(ConfigError.of(f, p + ".service-ports.first", ErrorCode.MIN_GREATER_THAN_MAX,
                            "service-ports.last"));
                }
            }
        }
    }

    private static void validateGroup(Checks c, String name, GroupConfig group) {
        String f = CloudHome.groupFileName(name);
        if (!ConfigNames.isValidName(name)) {
            c.add(ConfigError.of(f, "", ErrorCode.INVALID_FILE_NAME, ConfigNames.NAME + "\\.yml"));
        }
        c.version(f, group.schemaVersion(), GroupConfig.SCHEMA_VERSION);
        c.pattern(f, "template", group.template(), ConfigNames.TEMPLATE);
        c.required(f, "software", group.software());
        boolean version = c.pattern(f, "minecraft-version", group.minecraftVersion(), ConfigNames.MINECRAFT_VERSION);
        c.required(f, "java", group.java());
        c.required(f, "combat", group.combat());
        if (group.software() == ServerSoftware.PANDASPIGOT && version
                && !PANDASPIGOT_VERSION.equals(group.minecraftVersion())) {
            c.add(ConfigError.of(f, "minecraft-version", ErrorCode.SOFTWARE_VERSION_MISMATCH, "pandaspigot",
                    PANDASPIGOT_VERSION));
        }
        HeapConfig heap = group.heap();
        if (c.required(f, "heap", heap)) {
            boolean min = c.required(f, "heap.min", heap.min());
            boolean max = c.required(f, "heap.max", heap.max());
            if (min && max && heap.min().megabytes() > heap.max().megabytes()) {
                c.add(ConfigError.of(f, "heap.min", ErrorCode.MIN_GREATER_THAN_MAX, "heap.max"));
            }
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < group.catalog().size(); i++) {
            CatalogReference ref = group.catalog().get(i);
            String p = "catalog[" + i + "]";
            if (ref == null) {
                c.add(ConfigError.of(f, p, ErrorCode.REQUIRED));
                continue;
            }
            if (c.pattern(f, p + ".id", ref.id(), ConfigNames.CATALOG_ID) && !ids.add(ref.id())) {
                c.add(ConfigError.of(f, p + ".id", ErrorCode.DUPLICATE_ENTRY, ref.id()));
            }
            if (c.required(f, p + ".kind", ref.kind()) && group.software() != null) {
                boolean fabric = group.software() == ServerSoftware.FABRIC;
                if ((ref.kind() == CatalogKind.MOD && !fabric) || (ref.kind() == CatalogKind.PLUGIN && fabric)) {
                    c.add(ConfigError.of(f, p + ".kind", ErrorCode.CATALOG_KIND_MISMATCH,
                            lower(ref.kind()), lower(group.software())));
                }
            }
        }
        validateProcess(c, f, group.process());
    }

    private static void validateProcess(Checks c, String f, ProcessConfig process) {
        if (process == null) {
            return;
        }
        List<String> flags = process.jvmFlags();
        c.range(f, "process.jvm-flags", flags.size(), 0, ProcessConfig.MAX_JVM_FLAGS);
        for (int i = 0; i < flags.size() && i < ProcessConfig.MAX_JVM_FLAGS; i++) {
            String p = "process.jvm-flags[" + i + "]";
            if (c.pattern(f, p, flags.get(i), ProcessConfig.JVM_FLAG) && ProcessConfig.isReservedJvmFlag(flags.get(i))) {
                c.add(ConfigError.of(f, p, ErrorCode.INVALID_VALUE,
                        "a JVM flag other than -Xms or -Xmx (set under heap), -jar, -cp, -version or -help"));
            }
        }
        if (process.stopCommand() != null) {
            c.pattern(f, "process.stop-command", process.stopCommand(), ProcessConfig.STOP_COMMAND);
        }
        c.range(f, "process.stop-timeout", process.stopTimeout().duration().getSeconds(),
                ProcessConfig.MIN_STOP_TIMEOUT_SECONDS, ProcessConfig.MAX_STOP_TIMEOUT_SECONDS);
        RestartConfig restart = process.restart();
        c.range(f, "process.restart.max-attempts", restart.maxAttempts(), 1, RestartConfig.MAX_ATTEMPTS);
        boolean backoff = c.required(f, "process.restart.backoff", restart.backoff());
        boolean maxBackoff = c.required(f, "process.restart.max-backoff", restart.maxBackoff());
        if (backoff && maxBackoff
                && restart.backoff().duration().compareTo(restart.maxBackoff().duration()) > 0) {
            c.add(ConfigError.of(f, "process.restart.backoff", ErrorCode.MIN_GREATER_THAN_MAX,
                    "process.restart.max-backoff"));
        }
    }

    private static void validateTask(Checks c, String name, TaskConfig task, ConfigSnapshot snapshot) {
        String f = CloudHome.taskFileName(name);
        if (!ConfigNames.isValidName(name)) {
            c.add(ConfigError.of(f, "", ErrorCode.INVALID_FILE_NAME, ConfigNames.NAME + "\\.yml"));
        }
        c.version(f, task.schemaVersion(), TaskConfig.SCHEMA_VERSION);
        if (c.name(f, "group", task.group()) && !snapshot.groups().containsKey(task.group())) {
            c.add(ConfigError.of(f, "group", ErrorCode.UNKNOWN_GROUP, task.group()));
        }
        c.required(f, "mode", task.mode());
        ScalingConfig s = task.scaling();
        if (c.required(f, "scaling", s)) {
            boolean min = c.range(f, "scaling.min-services", s.minServices(), 0, MAX_SERVICES);
            boolean max = c.range(f, "scaling.max-services", s.maxServices(), 1, MAX_SERVICES);
            if (min && max && s.minServices() > s.maxServices()) {
                c.add(ConfigError.of(f, "scaling.min-services", ErrorCode.MIN_GREATER_THAN_MAX, "scaling.max-services"));
            }
            c.range(f, "scaling.players-per-service", s.playersPerService(), 1, MAX_PLAYERS_PER_SERVICE);
            c.range(f, "scaling.scale-up-at", s.scaleUpAt(), 1, 100);
            c.required(f, "scaling.scale-down-after", s.scaleDownAfter());
            c.required(f, "scaling.cooldown", s.cooldown());
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < task.nodes().size(); i++) {
            String node = task.nodes().get(i);
            String p = "nodes[" + i + "]";
            if (!c.name(f, p, node)) {
                continue;
            }
            if (!seen.add(node)) {
                c.add(ConfigError.of(f, p, ErrorCode.DUPLICATE_ENTRY, node));
            } else if (snapshot.nodes().find(node).isEmpty()) {
                c.add(ConfigError.of(f, p, ErrorCode.UNKNOWN_NODE, node));
            }
        }
    }

    private static void validateReferences(Checks c, ConfigSnapshot snapshot) {
        String nodeId = snapshot.cloud().nodeId();
        if (ConfigNames.isValidName(nodeId) && snapshot.nodes().find(nodeId).isEmpty()) {
            c.add(ConfigError.of(CloudHome.CLOUD_FILE, "node-id", ErrorCode.UNKNOWN_NODE, nodeId));
        }
    }

    /**
     * Fixed ports of this node must differ from each other and lie outside its service port range.
     * The Bedrock port counts although it is UDP, because Geyser also opens TCP for signaling.
     */
    private static void validatePorts(Checks c, ConfigSnapshot snapshot) {
        CloudConfig cloud = snapshot.cloud();
        Map<String, Integer> fixed = new LinkedHashMap<>();
        if (cloud.proxy() != null) {
            fixed.put("cloud.yml proxy.port", cloud.proxy().port());
            fixed.put("cloud.yml proxy.bedrock-port", cloud.proxy().bedrockPort());
        }
        if (cloud.legacyProxy() != null && cloud.legacyProxy().enabled()) {
            fixed.put("cloud.yml legacy-proxy.port", cloud.legacyProxy().port());
        }
        if (cloud.api() != null && cloud.api().enabled()) {
            fixed.put("cloud.yml api.port", cloud.api().port());
        }
        DatabaseConfig db = cloud.database();
        if (db != null && (db.mode() == DatabaseMode.MANAGED || db.mode() == DatabaseMode.DOCKER)) {
            fixed.put("cloud.yml database.port", db.port());
        }
        Optional<NodeConfig> local = snapshot.nodes().find(cloud.nodeId());
        int localIndex = local.map(n -> snapshot.nodes().nodes().indexOf(n)).orElse(-1);
        local.ifPresent(n -> fixed.put("nodes.yml nodes[" + localIndex + "].port", n.port()));

        Map<Integer, String> owners = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : fixed.entrySet()) {
            int port = entry.getValue();
            if (port < MIN_PORT || port > MAX_PORT) {
                continue; // already reported as out of range
            }
            String[] location = entry.getKey().split(" ", 2);
            String previous = owners.putIfAbsent(port, entry.getKey());
            if (previous != null) {
                c.add(ConfigError.of(location[0], location[1], ErrorCode.PORT_CONFLICT, previous));
            }
            if (local.isPresent() && local.get().servicePorts() != null && local.get().servicePorts().contains(port)
                    && !entry.getKey().startsWith("nodes.yml")) {
                c.add(ConfigError.of(location[0], location[1], ErrorCode.PORT_IN_SERVICE_RANGE,
                        local.get().servicePorts(), local.get().id()));
            }
        }
        // Every node: its own channel port must not be one it hands out to services.
        for (int i = 0; i < snapshot.nodes().nodes().size(); i++) {
            NodeConfig node = snapshot.nodes().nodes().get(i);
            if (node != null && node.servicePorts() != null && node.servicePorts().contains(node.port())) {
                c.add(ConfigError.of(CloudHome.NODES_FILE, "nodes[" + i + "].port", ErrorCode.PORT_IN_SERVICE_RANGE,
                        node.servicePorts(), node.id()));
            }
        }
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Collects errors; each check returns whether the value passed. */
    private static final class Checks {

        /** Compiled once; the keys are the constants of {@link ConfigNames}. */
        private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();
        private final List<ConfigError> errors = new ArrayList<>();

        void add(ConfigError error) {
            errors.add(error);
        }

        List<ConfigError> errors() {
            return List.copyOf(errors);
        }

        boolean required(String file, String path, Object value) {
            if (value == null) {
                add(ConfigError.of(file, path, ErrorCode.REQUIRED));
                return false;
            }
            return true;
        }

        boolean pattern(String file, String path, String value, String regex) {
            if (!required(file, path, value)) {
                return false;
            }
            if (!PATTERNS.computeIfAbsent(regex, Pattern::compile).matcher(value).matches()) {
                add(ConfigError.of(file, path, ErrorCode.PATTERN_MISMATCH, regex));
                return false;
            }
            return true;
        }

        /** A group, task or node name: the pattern plus the excluded device names. */
        boolean name(String file, String path, String value) {
            if (!required(file, path, value)) {
                return false;
            }
            if (!ConfigNames.isValidName(value)) {
                add(ConfigError.of(file, path, ErrorCode.PATTERN_MISMATCH, ConfigNames.NAME));
                return false;
            }
            return true;
        }

        boolean range(String file, String path, long value, long min, long max) {
            if (value < min || value > max) {
                add(ConfigError.of(file, path, ErrorCode.OUT_OF_RANGE, min, max));
                return false;
            }
            return true;
        }

        boolean port(String file, String path, int value) {
            return range(file, path, value, MIN_PORT, MAX_PORT);
        }

        void version(String file, int actual, int current) {
            if (actual != current) {
                add(ConfigError.of(file, "schema-version", ErrorCode.SCHEMA_VERSION_UNSUPPORTED, current));
            }
        }
    }
}
