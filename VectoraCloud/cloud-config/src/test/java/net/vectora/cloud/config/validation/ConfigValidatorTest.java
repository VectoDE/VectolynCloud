package net.vectora.cloud.config.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.Samples;
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
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.config.model.LegacyProxyConfig;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.model.NodeConfig;
import net.vectora.cloud.config.model.NodesConfig;
import net.vectora.cloud.config.model.PortRange;
import net.vectora.cloud.config.model.ProxyConfig;
import net.vectora.cloud.config.model.ScalingConfig;
import net.vectora.cloud.config.model.ServerSoftware;
import net.vectora.cloud.config.model.TaskConfig;
import net.vectora.cloud.config.model.TimeSpan;
import net.vectora.cloud.config.model.TlsConfig;
import org.junit.jupiter.api.Test;

class ConfigValidatorTest {

    private final ConfigSnapshot valid = Samples.snapshot();

    @Test
    void defaultsAndSamplesAreValid() {
        assertEquals(List.of(), ConfigValidator.validate(ConfigSnapshot.defaults()));
        assertEquals(List.of(), ConfigValidator.validate(valid));
    }

    @Test
    void portsMustBeInRange() {
        ConfigSnapshot s = withCloud(valid.cloud().withProxy(new ProxyConfig("0.0.0.0", 0, 70000)));
        assertEquals(Set.of("cloud.yml proxy.port OUT_OF_RANGE", "cloud.yml proxy.bedrock-port OUT_OF_RANGE"), codes(s));
        ConfigError error = ConfigValidator.validate(s).getFirst();
        assertEquals("Must be between 1 and 65535.", error.message(Language.EN));
        assertEquals("Muss zwischen 1 und 65535 liegen.", error.message(Language.DE));
    }

    @Test
    void fixedPortsOfThisNodeMustNotCollide() {
        ConfigSnapshot s = withCloud(valid.cloud()
                .withLegacyProxy(new LegacyProxyConfig(true, 25565))
                .withApi(new ApiConfig(true, "127.0.0.1", 3307, TlsConfig.disabled())));
        assertEquals(Set.of("cloud.yml legacy-proxy.port PORT_CONFLICT", "cloud.yml database.port PORT_CONFLICT"), codes(s));
        ConfigError conflict = ConfigValidator.validate(s).getFirst();
        assertEquals(List.of("cloud.yml proxy.port"), conflict.args());
    }

    @Test
    void disabledListenersAndExternalDatabasesDoNotTakePorts() {
        ConfigSnapshot s = withCloud(valid.cloud()
                .withLegacyProxy(new LegacyProxyConfig(false, 25565))
                .withDatabase(new DatabaseConfig(DatabaseMode.EXTERNAL, "db.example.org", 25565, "lp", "lp", null)));
        assertEquals(Set.of(), codes(s));
    }

    @Test
    void fixedPortsMustBeOutsideTheServicePortRange() {
        ConfigSnapshot s = withCloud(valid.cloud().withProxy(new ProxyConfig("0.0.0.0", 30005, 19132)));
        assertEquals(Set.of("cloud.yml proxy.port PORT_IN_SERVICE_RANGE"), codes(s));
        NodesConfig nodes = new NodesConfig(1, List.of(new NodeConfig("node-1", "127.0.0.1", 30010, new PortRange(30000, 30999))));
        assertEquals(Set.of("nodes.yml nodes[0].port PORT_IN_SERVICE_RANGE"), codes(valid.withNodes(nodes)));
    }

    @Test
    void heapMinMustNotExceedMax() {
        GroupConfig group = Samples.lobbyGroup().withHeap(new HeapConfig(MemorySize.parse("2G"), MemorySize.parse("1G")));
        assertEquals(Set.of("groups/lobby.yml heap.min MIN_GREATER_THAN_MAX"), codes(valid.withGroup("lobby", group)));
    }

    @Test
    void namesMustMatchThePattern() {
        assertEquals(Set.of("groups/Lobby.yml  INVALID_FILE_NAME"), codes(valid.withGroup("Lobby", Samples.lobbyGroup())));
        assertEquals(Set.of("groups/-lobby.yml  INVALID_FILE_NAME"), codes(valid.withGroup("-lobby", Samples.lobbyGroup())));
        assertEquals(Set.of("groups/" + "a".repeat(33) + ".yml  INVALID_FILE_NAME"),
                codes(valid.withGroup("a".repeat(33), Samples.lobbyGroup())));
        assertEquals(Set.of(), codes(valid.withGroup("bed_wars-2", Samples.lobbyGroup())));
        for (String bad : new String[] {"..", ".hidden", "a.b", "a\\b", "a/b", "con", "com1", "lpt9", "a b",
                "a".repeat(33)}) {
            assertEquals(Set.of(CloudHome.groupFileName(bad) + "  INVALID_FILE_NAME"),
                    codes(valid.withGroup(bad, Samples.lobbyGroup())), bad);
        }
        for (String good : new String[] {"console", "con1", "com10", "lpt"}) {
            assertEquals(Set.of(), codes(valid.withGroup(good, Samples.lobbyGroup())), good);
        }
        TaskConfig lobby = Samples.lobbyTask();
        assertEquals(Set.of("tasks/lobby.yml group PATTERN_MISMATCH"), codes(valid.withTask("lobby",
                new TaskConfig(lobby.schemaVersion(), "nul", lobby.mode(), lobby.scaling(), lobby.nodes()))));
        assertEquals(Set.of("cloud.yml network-name PATTERN_MISMATCH"),
                codes(withCloud(valid.cloud().withNetworkName(" leading space"))));
        assertEquals(Set.of(), codes(withCloud(valid.cloud().withNetworkName("Vectora Netzwerk"))));
    }

    @Test
    void tasksMustReferToExistingGroupsAndNodes() {
        TaskConfig task = new TaskConfig(1, "missing", Samples.lobbyTask().mode(), Samples.lobbyTask().scaling(),
                List.of("node-1", "node-9", "node-1"));
        assertEquals(Set.of(
                "tasks/lobby.yml group UNKNOWN_GROUP",
                "tasks/lobby.yml nodes[1] UNKNOWN_NODE",
                "tasks/lobby.yml nodes[2] DUPLICATE_ENTRY"), codes(valid.withTask("lobby", task)));
        assertEquals(Set.of("tasks/lobby.yml group UNKNOWN_GROUP"), codes(valid.withoutGroup("lobby")));
    }

    @Test
    void scalingLimits() {
        ScalingConfig scaling = new ScalingConfig(5, 2, 0, 101, null, TimeSpan.parse("1m"));
        assertEquals(Set.of(
                "tasks/lobby.yml scaling.min-services MIN_GREATER_THAN_MAX",
                "tasks/lobby.yml scaling.players-per-service OUT_OF_RANGE",
                "tasks/lobby.yml scaling.scale-up-at OUT_OF_RANGE",
                "tasks/lobby.yml scaling.scale-down-after REQUIRED"),
                codes(valid.withTask("lobby", Samples.lobbyTask().withScaling(scaling))));
    }

    @Test
    void cloudNodeMustExist() {
        assertEquals(Set.of("cloud.yml node-id UNKNOWN_NODE"), codes(withCloud(valid.cloud().withNodeId("node-2"))));
        NodesConfig none = new NodesConfig(1, List.of());
        assertTrue(codes(valid.withNodes(none)).contains("nodes.yml nodes EMPTY"));
    }

    @Test
    void nodeIdsAreUnique() {
        NodesConfig twice = new NodesConfig(1, List.of(NodeConfig.defaults(),
                new NodeConfig("node-1", "10.0.0.2", 7010, new PortRange(31000, 30000))));
        assertEquals(Set.of("nodes.yml nodes[1].id DUPLICATE_ENTRY", "nodes.yml nodes[1].service-ports.first MIN_GREATER_THAN_MAX"),
                codes(valid.withNodes(twice)));
    }

    @Test
    void catalogEntriesMustFitTheSoftware() {
        GroupConfig fabric = new GroupConfig(1, "Mod-Server", ServerSoftware.FABRIC, "26.3", Samples.lobbyGroup().java(),
                Samples.lobbyGroup().heap(), List.of(
                        new CatalogReference("lithium", CatalogKind.MOD),
                        new CatalogReference("luckperms", CatalogKind.PLUGIN),
                        new CatalogReference("spark", CatalogKind.TRANSITIONAL),
                        new CatalogReference("spark", CatalogKind.TRANSITIONAL)),
                Samples.lobbyGroup().combat());
        assertEquals(Set.of("groups/mod.yml catalog[1].kind CATALOG_KIND_MISMATCH", "groups/mod.yml catalog[3].id DUPLICATE_ENTRY"),
                codes(valid.withGroup("mod", fabric)));
        GroupConfig paperWithMod = Samples.lobbyGroup().withCatalog(List.of(new CatalogReference("lithium", CatalogKind.MOD)));
        assertEquals(Set.of("groups/lobby.yml catalog[0].kind CATALOG_KIND_MISMATCH"), codes(valid.withGroup("lobby", paperWithMod)));
    }

    @Test
    void pandaSpigotRunsOnly188() {
        GroupConfig legacy = new GroupConfig(1, "BedWars-Legacy", ServerSoftware.PANDASPIGOT, "1.12.2",
                Samples.lobbyGroup().java(), Samples.lobbyGroup().heap(), List.of(), Samples.lobbyGroup().combat());
        assertEquals(Set.of("groups/bedwars.yml minecraft-version SOFTWARE_VERSION_MISMATCH"), codes(valid.withGroup("bedwars", legacy)));
    }

    @Test
    void databaseModeDecidesAboutTheDockerImage() {
        DatabaseConfig docker = DatabaseConfig.defaults().withMode(DatabaseMode.DOCKER);
        assertEquals(Set.of(), codes(withCloud(valid.cloud().withDatabase(docker))));
        DatabaseConfig dockerWithoutImage = new DatabaseConfig(DatabaseMode.DOCKER, "127.0.0.1", 3307, "lp", "lp", null);
        assertEquals(Set.of("cloud.yml database.docker-image REQUIRED"), codes(withCloud(valid.cloud().withDatabase(dockerWithoutImage))));
        DatabaseConfig managedWithImage = new DatabaseConfig(DatabaseMode.MANAGED, "127.0.0.1", 3307, "lp", "lp", "mariadb:11");
        assertEquals(Set.of("cloud.yml database.docker-image NOT_ALLOWED"), codes(withCloud(valid.cloud().withDatabase(managedWithImage))));
        DatabaseConfig badNames = new DatabaseConfig(DatabaseMode.EXTERNAL, "db host", 3306, "lp-db", "", null);
        assertEquals(Set.of("cloud.yml database.host PATTERN_MISMATCH", "cloud.yml database.name PATTERN_MISMATCH",
                "cloud.yml database.user PATTERN_MISMATCH"), codes(withCloud(valid.cloud().withDatabase(badNames))));
    }

    @Test
    void publicApiNeedsTls() {
        ApiConfig open = new ApiConfig(true, "0.0.0.0", 8085, TlsConfig.disabled());
        assertEquals(Set.of("cloud.yml api.tls.enabled API_TLS_REQUIRED"), codes(withCloud(valid.cloud().withApi(open))));
        ApiConfig tlsWithoutKeystore = new ApiConfig(true, "0.0.0.0", 8085, new TlsConfig(true, null));
        assertEquals(Set.of("cloud.yml api.tls.keystore REQUIRED"), codes(withCloud(valid.cloud().withApi(tlsWithoutKeystore))));
        ApiConfig escaping = new ApiConfig(true, "0.0.0.0", 8085, new TlsConfig(true, "../keystore.p12"));
        assertEquals(Set.of("cloud.yml api.tls.keystore PATTERN_MISMATCH"), codes(withCloud(valid.cloud().withApi(escaping))));
        assertEquals(Set.of(), codes(withCloud(valid.cloud().withApi(new ApiConfig(true, "0.0.0.0", 8085,
                new TlsConfig(true, "secrets/api.p12"))))));
        assertEquals(Set.of(), codes(withCloud(valid.cloud().withApi(new ApiConfig(true, "localhost", 8085, TlsConfig.disabled())))));
    }

    @Test
    void setupCannotCompleteWithoutEula() {
        assertEquals(Set.of("cloud.yml setup-complete EULA_REQUIRED"), codes(withCloud(valid.cloud().withSetupComplete(true))));
        assertEquals(Set.of(), codes(withCloud(valid.cloud().withEulaAccepted(true).withSetupComplete(true))));
    }

    @Test
    void missingSectionsAreRequired() {
        CloudConfig empty = new CloudConfig(1, null, null, false, false, null, null, null, null, null, null);
        assertEquals(Set.of(
                "cloud.yml network-name REQUIRED", "cloud.yml language REQUIRED", "cloud.yml node-id REQUIRED",
                "cloud.yml servers REQUIRED", "cloud.yml proxy REQUIRED", "cloud.yml legacy-proxy REQUIRED",
                "cloud.yml database REQUIRED", "cloud.yml api REQUIRED"), codes(withCloud(empty)));
        GroupConfig bare = new GroupConfig(1, null, null, null, null, null, null, null);
        assertEquals(Set.of(
                "groups/lobby.yml template REQUIRED", "groups/lobby.yml software REQUIRED",
                "groups/lobby.yml minecraft-version REQUIRED", "groups/lobby.yml java REQUIRED",
                "groups/lobby.yml heap REQUIRED", "groups/lobby.yml combat REQUIRED"), codes(valid.withGroup("lobby", bare)));
    }

    @Test
    void schemaVersionMustBeCurrent() {
        assertEquals(Set.of("groups/lobby.yml schema-version SCHEMA_VERSION_UNSUPPORTED"),
                codes(valid.withGroup("lobby", new GroupConfig(2, "Lobby-Server", ServerSoftware.PAPER, "26.3",
                        Samples.lobbyGroup().java(), Samples.lobbyGroup().heap(), List.of(), Samples.lobbyGroup().combat()))));
    }

    @Test
    void loopbackDetection() {
        for (String host : new String[] {"localhost", "LOCALHOST", "127.0.0.1", "127.1.2.3", "::1", "[::1]"}) {
            assertTrue(ConfigNames.isLoopback(host), host);
        }
        for (String host : new String[] {"0.0.0.0", "10.0.0.1", "::", "localhost.example.org", "1127.0.0.1"}) {
            assertTrue(!ConfigNames.isLoopback(host), host);
        }
    }

    private ConfigSnapshot withCloud(CloudConfig cloud) {
        return valid.withCloud(cloud);
    }

    private static Set<String> codes(ConfigSnapshot snapshot) {
        return ConfigValidator.validate(snapshot).stream()
                .map(e -> e.file() + " " + e.path() + " " + e.code())
                .collect(Collectors.toSet());
    }
}
