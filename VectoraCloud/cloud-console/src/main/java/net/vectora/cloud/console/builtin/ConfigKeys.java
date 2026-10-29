package net.vectora.cloud.console.builtin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.model.ApiConfig;
import net.vectora.cloud.config.model.CloudConfig;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.config.model.LegacyProxyConfig;
import net.vectora.cloud.config.model.ProxyConfig;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.model.TlsConfig;
import net.vectora.cloud.config.model.WantedServers;
import net.vectora.cloud.config.operation.ConfigOperation;
import net.vectora.cloud.config.operation.SetApi;
import net.vectora.cloud.config.operation.SetDatabase;
import net.vectora.cloud.config.operation.SetDatabaseMode;
import net.vectora.cloud.config.operation.SetLanguage;
import net.vectora.cloud.config.operation.SetLegacyProxy;
import net.vectora.cloud.config.operation.SetNetworkName;
import net.vectora.cloud.config.operation.SetProxy;
import net.vectora.cloud.config.operation.SetServerWanted;

/**
 * The keys of {@code cloud.yml} that {@code config get/set} know, with the field path of the file
 * ({@code proxy.port}). Each key turns a typed value into a {@link ConfigOperation} of cloud-config;
 * {@code ConfigService.apply} then runs the same validation as for a hand-edited file.
 *
 * <p>{@code schema-version} is not a key: the cloud manages it. Secrets are not in {@code cloud.yml}.
 * Optional texts ({@code database.docker-image}, {@code api.tls.keystore}) are cleared with {@code ""}.
 */
final class ConfigKeys {

    private static final String FILE = CloudHome.CLOUD_FILE;

    /** One key. */
    record Key(String name, Function<CloudConfig, String> getter, Setter setter, List<String> values) {
    }

    /** Builds the operation for a typed value. */
    @FunctionalInterface
    interface Setter {
        ConfigOperation operation(ConfigSnapshot current, String value) throws ConfigException;
    }

    private static final Map<String, Key> KEYS = new LinkedHashMap<>();

    static {
        List<String> bool = List.of("true", "false");
        add("network-name", CloudConfig::networkName, (s, v) -> new SetNetworkName(v), List.of());
        add("language", c -> Values.name(c.language()),
                (s, v) -> new SetLanguage(Values.enumValue(FILE, "language", v, Language.class)), Values.names(Language.class));
        add("eula-accepted", c -> String.valueOf(c.eulaAccepted()), (s, v) -> {
            boolean value = Values.bool(FILE, "eula-accepted", v);
            return snapshot -> snapshot.withCloud(snapshot.cloud().withEulaAccepted(value));
        }, bool);
        add("setup-complete", c -> String.valueOf(c.setupComplete()), (s, v) -> {
            boolean value = Values.bool(FILE, "setup-complete", v);
            return snapshot -> snapshot.withCloud(snapshot.cloud().withSetupComplete(value));
        }, bool);
        add("node-id", CloudConfig::nodeId,
                (s, v) -> snapshot -> snapshot.withCloud(snapshot.cloud().withNodeId(v)), List.of());
        for (ServerKind kind : ServerKind.values()) {
            String key = "servers." + kind.key();
            add(key, c -> String.valueOf(servers(c).isWanted(kind)),
                    (s, v) -> new SetServerWanted(kind, Values.bool(FILE, key, v)), bool);
        }
        add("proxy.bind", c -> proxy(c).bind(),
                (s, v) -> new SetProxy(new ProxyConfig(v, proxy(s.cloud()).port(), proxy(s.cloud()).bedrockPort())), List.of());
        add("proxy.port", c -> String.valueOf(proxy(c).port()), (s, v) -> new SetProxy(new ProxyConfig(
                proxy(s.cloud()).bind(), Values.integer(FILE, "proxy.port", v), proxy(s.cloud()).bedrockPort())), List.of());
        add("proxy.bedrock-port", c -> String.valueOf(proxy(c).bedrockPort()), (s, v) -> new SetProxy(new ProxyConfig(
                proxy(s.cloud()).bind(), proxy(s.cloud()).port(), Values.integer(FILE, "proxy.bedrock-port", v))), List.of());
        add("legacy-proxy.enabled", c -> String.valueOf(legacy(c).enabled()), (s, v) -> new SetLegacyProxy(
                Values.bool(FILE, "legacy-proxy.enabled", v), legacy(s.cloud()).port()), bool);
        add("legacy-proxy.port", c -> String.valueOf(legacy(c).port()), (s, v) -> new SetLegacyProxy(
                legacy(s.cloud()).enabled(), Values.integer(FILE, "legacy-proxy.port", v)), List.of());
        add("database.mode", c -> Values.name(database(c).mode()),
                (s, v) -> new SetDatabaseMode(Values.enumValue(FILE, "database.mode", v, DatabaseMode.class)),
                Values.names(DatabaseMode.class));
        add("database.host", c -> database(c).host(), (s, v) -> database(s, d ->
                new DatabaseConfig(d.mode(), v, d.port(), d.name(), d.user(), d.dockerImage())), List.of());
        add("database.port", c -> String.valueOf(database(c).port()), (s, v) -> {
            int port = Values.integer(FILE, "database.port", v);
            return database(s, d -> new DatabaseConfig(d.mode(), d.host(), port, d.name(), d.user(), d.dockerImage()));
        }, List.of());
        add("database.name", c -> database(c).name(), (s, v) -> database(s, d ->
                new DatabaseConfig(d.mode(), d.host(), d.port(), v, d.user(), d.dockerImage())), List.of());
        add("database.user", c -> database(c).user(), (s, v) -> database(s, d ->
                new DatabaseConfig(d.mode(), d.host(), d.port(), d.name(), v, d.dockerImage())), List.of());
        add("database.docker-image", c -> orEmpty(database(c).dockerImage()), (s, v) -> database(s, d ->
                new DatabaseConfig(d.mode(), d.host(), d.port(), d.name(), d.user(), Values.optionalText(v))), List.of());
        add("api.enabled", c -> String.valueOf(api(c).enabled()), (s, v) -> {
            boolean value = Values.bool(FILE, "api.enabled", v);
            ApiConfig a = api(s.cloud());
            return new SetApi(new ApiConfig(value, a.bind(), a.port(), a.tls()));
        }, bool);
        add("api.bind", c -> api(c).bind(), (s, v) -> {
            ApiConfig a = api(s.cloud());
            return new SetApi(new ApiConfig(a.enabled(), v, a.port(), a.tls()));
        }, List.of());
        add("api.port", c -> String.valueOf(api(c).port()), (s, v) -> {
            int port = Values.integer(FILE, "api.port", v);
            ApiConfig a = api(s.cloud());
            return new SetApi(new ApiConfig(a.enabled(), a.bind(), port, a.tls()));
        }, List.of());
        add("api.tls.enabled", c -> String.valueOf(tls(c).enabled()), (s, v) -> {
            boolean value = Values.bool(FILE, "api.tls.enabled", v);
            ApiConfig a = api(s.cloud());
            return new SetApi(new ApiConfig(a.enabled(), a.bind(), a.port(), new TlsConfig(value, tls(s.cloud()).keystore())));
        }, bool);
        add("api.tls.keystore", c -> orEmpty(tls(c).keystore()), (s, v) -> {
            ApiConfig a = api(s.cloud());
            return new SetApi(new ApiConfig(a.enabled(), a.bind(), a.port(),
                    new TlsConfig(tls(s.cloud()).enabled(), Values.optionalText(v))));
        }, List.of());
    }

    private ConfigKeys() {
    }

    private static void add(String name, Function<CloudConfig, String> getter, Setter setter, List<String> values) {
        KEYS.put(name, new Key(name, getter, setter, values));
    }

    /** Every key, in file order. */
    static List<Key> all() {
        return new ArrayList<>(KEYS.values());
    }

    /** Key names, in file order. */
    static List<String> names() {
        return new ArrayList<>(KEYS.keySet());
    }

    /** The key with this name. */
    static Optional<Key> find(String name) {
        return Optional.ofNullable(KEYS.get(name));
    }

    // The model allows null sections in a snapshot built in code; files always have them.

    private static WantedServers servers(CloudConfig c) {
        return c.servers() == null ? WantedServers.none() : c.servers();
    }

    private static ProxyConfig proxy(CloudConfig c) {
        return c.proxy() == null ? ProxyConfig.defaults() : c.proxy();
    }

    private static LegacyProxyConfig legacy(CloudConfig c) {
        return c.legacyProxy() == null ? LegacyProxyConfig.defaults() : c.legacyProxy();
    }

    private static DatabaseConfig database(CloudConfig c) {
        return c.database() == null ? DatabaseConfig.defaults() : c.database();
    }

    private static ConfigOperation database(ConfigSnapshot s, Function<DatabaseConfig, DatabaseConfig> change) {
        return new SetDatabase(change.apply(database(s.cloud())));
    }

    private static ApiConfig api(CloudConfig c) {
        return c.api() == null ? ApiConfig.defaults() : c.api();
    }

    private static TlsConfig tls(CloudConfig c) {
        ApiConfig api = api(c);
        return api.tls() == null ? TlsConfig.disabled() : api.tls();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
