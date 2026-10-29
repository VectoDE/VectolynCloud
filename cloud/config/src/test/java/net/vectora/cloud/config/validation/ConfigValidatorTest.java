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
import net.vectora.cloud.config.model.MemoryReserve;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.model.NodeConfig;
import net.vectora.cloud.config.model.NodesConfig;
import net.vectora.cloud.config.model.PortRange;
import net.vectora.cloud.config.model.ProcessConfig;
import net.vectora.cloud.config.model.ProxyConfig;
import net.vectora.cloud.config.model.RestartConfig;
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
    void processSectionIsChecked() {
        GroupConfig lobby = Samples.lobbyGroup();
        ProcessConfig good = new ProcessConfig(List.of("-XX:+UseG1GC", "-Dvectora.flag=a,b"), "stop", TimeSpan.parse("30s"),
                RestartConfig.defaults());
        assertEquals(Set.of(), codes(valid.withGroup("lobby", lobby.withProcess(good))));

        ProcessConfig bad = new ProcessConfig(List.of("-Xmx4G", "-jar", "--class-path=x", "no-dash", "-Dx=a b"), "/stop",
                TimeSpan.parse("0s"), new RestartConfig(true, 0, TimeSpan.parse("10m"), TimeSpan.parse("1m")));
        assertEquals(Set.of(
                "groups/lobby.yml process.jvm-flags[0] INVALID_VALUE",
                "groups/lobby.yml process.jvm-flags[1] INVALID_VALUE",
                "groups/lobby.yml process.jvm-flags[2] INVALID_VALUE",
                "groups/lobby.yml process.jvm-flags[3] PATTERN_MISMATCH",
                "groups/lobby.yml process.jvm-flags[4] PATTERN_MISMATCH",
                "groups/lobby.yml process.stop-command PATTERN_MISMATCH",
                "groups/lobby.yml process.stop-timeout OUT_OF_RANGE",
                "groups/lobby.yml process.restart.max-attempts OUT_OF_RANGE",
                "groups/lobby.yml process.restart.backoff MIN_GREATER_THAN_MAX"),
                codes(valid.withGroup("lobby", lobby.withProcess(bad))));

        List<String> many = java.util.Collections.nCopies(ProcessConfig.MAX_JVM_FLAGS + 1, "-XX:+UseG1GC");
        assertEquals(Set.of("groups/lobby.yml process.jvm-flags OUT_OF_RANGE"),
                codes(valid.withGroup("lobby", lobby.withProcess(new ProcessConfig(many, null, null, null)))));
    }

    @Test
    void drainTimeoutIsOptionalAndLimited() {
        GroupConfig lobby = Samples.lobbyGroup();
        assertEquals(GroupConfig.DEFAULT_DRAIN_TIMEOUT, lobby.drainTimeoutOrDefault(), "missing: 30s");
        assertEquals(TimeSpan.parse("30s"), GroupConfig.DEFAULT_DRAIN_TIMEOUT);
        for (String ok : List.of("5s", "45s", "10m")) {
            assertEquals(Set.of(), codes(valid.withGroup("lobby", lobby.withDrainTimeout(TimeSpan.parse(ok)))), ok);
        }
        for (String bad : List.of("0s", "4s", "601s", "1h")) {
            assertEquals(Set.of("groups/lobby.yml drain-timeout OUT_OF_RANGE"),
                    codes(valid.withGroup("lobby", lobby.withDrainTimeout(TimeSpan.parse(bad)))), bad);
        }
        ConfigError error = ConfigValidator.validate(valid.withGroup("lobby", lobby.withDrainTimeout(TimeSpan.parse("1h"))))
                .getFirst();
        assertEquals("Must be between 5s and 10m.", error.message(Language.EN));
        assertEquals(TimeSpan.parse("45s"), lobby.withDrainTimeout(TimeSpan.parse("45s")).drainTimeoutOrDefault());
        assertEquals(GroupConfig.DEFAULT_DRAIN_TIMEOUT,
                lobby.withDrainTimeout(TimeSpan.parse("45s")).withDrainTimeout(null).drainTimeoutOrDefault(), "null: default");
    }

    @Test
    void reservedJvmFlagsAreRecognised() {
        for (String flag : List.of("-Xms1G", "-Xmx1G", "-jar", "-cp", "-classpath", "--class-path", "--class-path=a",
                "-version", "--version", "-help", "-?", "-X", "-XX:Flags=x", "-XX:VMOptionsFile=x")) {
            assertTrue(ProcessConfig.isReservedJvmFlag(flag), flag);
        }
        for (String flag : List.of("-XX:+UseG1GC", "-Xss1M", "-Dcom.mojang.eula.agree=true", "-javaagent:spark.jar")) {
            assertEquals(false, ProcessConfig.isReservedJvmFlag(flag), flag);
        }
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
    void theNetherNetPortAfterTheBedrockPortIsAFixedPortToo() {
        // Geyser runs RakNet and NetherNet ("both"): NetherNet takes UDP bedrock-port + 1.
        ConfigSnapshot java = withCloud(valid.cloud().withProxy(new ProxyConfig("0.0.0.0", 25565, 25564)));
        assertEquals(Set.of("cloud.yml proxy.bedrock-port PORT_CONFLICT"), codes(java));
        assertEquals(List.of("cloud.yml proxy.port"), ConfigValidator.validate(java).getFirst().args());
        ConfigSnapshot range = withCloud(valid.cloud().withProxy(new ProxyConfig("0.0.0.0", 25565, 29999)));
        assertEquals(Set.of("cloud.yml proxy.bedrock-port PORT_IN_SERVICE_RANGE"), codes(range));
        ConfigSnapshot last = withCloud(valid.cloud().withProxy(new ProxyConfig("0.0.0.0", 25565, 65535)));
        assertEquals(Set.of("cloud.yml proxy.bedrock-port OUT_OF_RANGE"), codes(last));
        // Counter-check: the port right before the Java port and the highest possible one are fine.
        assertEquals(Set.of(), codes(withCloud(valid.cloud().withProxy(new ProxyConfig("0.0.0.0", 25565, 25563)))));
        assertEquals(Set.of(), codes(withCloud(valid.cloud().withProxy(new ProxyConfig("0.0.0.0", 25565, 65534)))));
        assertEquals(19133, ProxyConfig.defaults().netherNetPort());
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
                new TaskConfig(lobby.schemaVersion(), "nul", lobby.scaling(), lobby.nodes()))));
        assertEquals(Set.of("cloud.yml network-name PATTERN_MISMATCH"),
                codes(withCloud(valid.cloud().withNetworkName(" leading space"))));
        assertEquals(Set.of(), codes(withCloud(valid.cloud().withNetworkName("Vectora Netzwerk"))));
    }

    @Test
    void tasksMustReferToExistingGroupsAndNodes() {
        TaskConfig task = new TaskConfig(TaskConfig.SCHEMA_VERSION, "missing", Samples.lobbyTask().scaling(),
                List.of("node-1", "node-9", "node-1"));
        assertEquals(Set.of(
                "tasks/lobby.yml group UNKNOWN_GROUP",
                "tasks/lobby.yml nodes[1] UNKNOWN_NODE",
                "tasks/lobby.yml nodes[2] DUPLICATE_ENTRY"), codes(valid.withTask("lobby", task)));
        assertEquals(Set.of("tasks/lobby.yml group UNKNOWN_GROUP"), codes(valid.withoutGroup("lobby")));
    }

    @Test
    void scalingLimits() {
        ScalingConfig scaling = new ScalingConfig(0, 5, 2, 0, 101, 50, null, TimeSpan.parse("1m"));
        assertEquals(Set.of(
                "tasks/lobby.yml scaling.min-services MIN_GREATER_THAN_MAX",
                "tasks/lobby.yml scaling.players-per-service OUT_OF_RANGE",
                "tasks/lobby.yml scaling.scale-up-at OUT_OF_RANGE",
                "tasks/lobby.yml scaling.scale-down-after REQUIRED"),
                codes(valid.withTask("lobby", Samples.lobbyTask().withScaling(scaling))));
    }

    @Test
    void staticServicesMustNotExceedMinServices() {
        ScalingConfig base = Samples.lobbyTask().scaling();
        assertEquals(Set.of("tasks/lobby.yml scaling.static-services STATIC_ABOVE_MIN"),
                codes(valid.withTask("lobby", Samples.lobbyTask().withScaling(base.withCounts(2, 1, 4)))));
        assertEquals(Set.of("tasks/lobby.yml scaling.static-services OUT_OF_RANGE"),
                codes(valid.withTask("lobby", Samples.lobbyTask().withScaling(base.withCounts(-1, 1, 4)))));
        // Counter-checks: the pure and mixed forms are valid.
        assertEquals(Set.of(), codes(valid.withTask("lobby", Samples.lobbyTask().withScaling(base.withCounts(3, 3, 3)))));
        assertEquals(Set.of(), codes(valid.withTask("lobby", Samples.lobbyTask().withScaling(base.withCounts(0, 1, 8)))));
        assertEquals(Set.of(), codes(valid.withTask("lobby", Samples.lobbyTask().withScaling(base.withCounts(1, 2, 4)))));
        assertEquals(Set.of(), codes(valid.withTask("lobby", Samples.lobbyTask().withScaling(base.withCounts(0, 0, 1)))));
    }

    @Test
    void scaleDownBelowMustBeBelowScaleUpAt() {
        ScalingConfig base = Samples.lobbyTask().scaling().withScaleUpAt(75);
        assertEquals(Set.of("tasks/lobby.yml scaling.scale-down-below SCALE_DOWN_NOT_BELOW_UP"),
                codes(valid.withTask("lobby", Samples.lobbyTask().withScaling(base.withScaleDownBelow(75)))));
        assertEquals(Set.of("tasks/lobby.yml scaling.scale-down-below OUT_OF_RANGE"),
                codes(valid.withTask("lobby", Samples.lobbyTask().withScaling(base.withScaleDownBelow(100)))));
        assertEquals(Set.of("tasks/lobby.yml scaling.scale-down-below OUT_OF_RANGE"),
                codes(valid.withTask("lobby", Samples.lobbyTask().withScaling(base.withScaleDownBelow(-1)))));
        // Counter-checks: just below, and 0.
        assertEquals(Set.of(), codes(valid.withTask("lobby", Samples.lobbyTask().withScaling(base.withScaleDownBelow(74)))));
        assertEquals(Set.of(), codes(valid.withTask("lobby", Samples.lobbyTask().withScaling(base.withScaleDownBelow(0)))));
    }

    @Test
    void scaleDownBelowAndStaticAboveMinHaveGermanMessages() {
        ConfigError error = ConfigError.of("tasks/lobby.yml", "scaling.static-services", ErrorCode.STATIC_ABOVE_MIN,
                "scaling.min-services");
        assertTrue(error.describe(Language.DE).contains("statische Dienste"), error.describe(Language.DE));
        assertTrue(error.describe(Language.EN).contains("static services"), error.describe(Language.EN));
        ConfigError down = ConfigError.of("tasks/lobby.yml", "scaling.scale-down-below", ErrorCode.SCALE_DOWN_NOT_BELOW_UP,
                "scaling.scale-up-at");
        assertTrue(down.describe(Language.DE).contains("kleiner"), down.describe(Language.DE));
        assertEquals("scale-down-not-below-up", ErrorCode.SCALE_DOWN_NOT_BELOW_UP.code());
    }

    @Test
    void nodeMaxMemoryIsOptionalAndHasALowerLimit() {
        NodeConfig node = NodeConfig.defaults();
        assertEquals(Set.of(), codes(valid.withNodes(new NodesConfig(1, List.of(node)))));
        assertEquals(Set.of(), codes(valid.withNodes(new NodesConfig(1, List.of(node.withMaxMemory(MemorySize.parse("8G")))))));
        assertEquals(Set.of("nodes.yml nodes[0].max-memory OUT_OF_RANGE"),
                codes(valid.withNodes(new NodesConfig(1, List.of(node.withMaxMemory(MemorySize.parse("256M")))))));
        assertEquals(Set.of(), codes(valid.withNodes(new NodesConfig(1, List.of(node.withMaxMemory(MemorySize.parse("512M")))))));
    }

    @Test
    void memoryReserveIsOptionalAndLimitedPerField() {
        NodeConfig node = NodeConfig.defaults();
        assertEquals(MemoryReserve.defaults(), node.memoryReserve(), "missing: every default");
        assertEquals(List.of(1024L, 256L, 512L), List.of(node.memoryReserve().system().megabytes(),
                node.memoryReserve().nodeOverhead().megabytes(), node.memoryReserve().database().megabytes()));
        MemoryReserve partial = new MemoryReserve(MemorySize.parse("2G"), null, null);
        NodeConfig some = node.withMemoryReserve(partial);
        assertEquals(new MemoryReserve(MemorySize.parse("2G"), MemorySize.parse("256M"), MemorySize.parse("512M")),
                some.memoryReserve(), "missing fields keep their default");
        assertEquals(Set.of(), codes(valid.withNodes(new NodesConfig(1, List.of(some)))));
        MemoryReserve max = new MemoryReserve(MemorySize.parse("64G"), MemorySize.parse("64G"), MemorySize.parse("64G"));
        assertEquals(Set.of(), codes(valid.withNodes(new NodesConfig(1, List.of(node.withMemoryReserve(max))))),
                "counter-check: 64G is allowed");
        MemoryReserve tooMuch = new MemoryReserve(MemorySize.parse("65G"), MemorySize.parse("1024G"), MemorySize.parse("1M"));
        assertEquals(Set.of("nodes.yml nodes[0].memory-reserve.system OUT_OF_RANGE",
                        "nodes.yml nodes[0].memory-reserve.node-overhead OUT_OF_RANGE"),
                codes(valid.withNodes(new NodesConfig(1, List.of(node.withMemoryReserve(tooMuch))))));
        ConfigError error = ConfigValidator.validate(valid.withNodes(new NodesConfig(1, List.of(node.withMemoryReserve(tooMuch)))))
                .getFirst();
        assertEquals("Must be between 1M and 64G.", error.message(Language.EN));
        assertEquals(null, node.withMemoryReserve(new MemoryReserve(null, null, null)).reserve(), "an empty section is dropped");
    }

    @Test
    void reserveCountsTheDatabaseOnlyWhenTheCloudRunsIt() {
        var kind = net.vectora.cloud.config.model.ServerKind.DATABASE;
        CloudConfig cloud = valid.cloud().withServers(valid.cloud().servers().with(kind, true));
        MemoryReserve reserve = MemoryReserve.defaults();
        assertEquals(1024 + 256 + 512, reserve.megabytes(true));
        assertEquals(1024 + 256, reserve.megabytes(false));
        assertEquals(true, MemoryReserve.countsDatabase(cloud.withDatabase(cloud.database().withMode(DatabaseMode.MANAGED))));
        assertEquals(true, MemoryReserve.countsDatabase(cloud.withDatabase(cloud.database().withMode(DatabaseMode.DOCKER))));
        assertEquals(false, MemoryReserve.countsDatabase(cloud.withServers(cloud.servers().with(kind, false))
                .withDatabase(cloud.database().withMode(DatabaseMode.MANAGED))), "switched off");
        assertEquals(false, MemoryReserve.countsDatabase(cloud.withDatabase(cloud.database().withMode(DatabaseMode.H2))));
        assertEquals(false, MemoryReserve.countsDatabase(cloud.withDatabase(cloud.database().withMode(DatabaseMode.EXTERNAL))));
    }

    @Test
    void cloudNodeMustExist() {
        assertEquals(Set.of("cloud.yml node-id UNKNOWN_NODE"), codes(withCloud(valid.cloud().withNodeId("node-2"))));
        NodesConfig none = new NodesConfig(1, List.of());
        assertTrue(codes(valid.withNodes(none)).contains("nodes.yml nodes EMPTY"));
    }

    @Test
    void theFallbackGroupMustBeAValidExistingGroup() {
        assertEquals("lobby", valid.cloud().fallbackGroupOrDefault(), "default");
        assertEquals(Set.of("cloud.yml fallback-group UNKNOWN_GROUP"),
                codes(withCloud(valid.cloud().withFallbackGroup("hub"))));
        assertEquals(Set.of("cloud.yml fallback-group PATTERN_MISMATCH"),
                codes(withCloud(valid.cloud().withFallbackGroup("Hub!"))));
        assertEquals(Set.of("cloud.yml fallback-group PATTERN_MISMATCH"),
                codes(withCloud(valid.cloud().withFallbackGroup("con"))), "a device name is no group name");
        // Counter-checks: an existing group, and the default without a lobby group.
        ConfigSnapshot hub = withCloud(valid.cloud().withFallbackGroup("hub")).withGroup("hub", Samples.lobbyGroup());
        assertEquals(Set.of(), codes(hub));
        assertEquals("hub", hub.cloud().fallbackGroupOrDefault());
        assertEquals(Set.of(), codes(valid.withoutTask("lobby").withoutGroup("lobby")),
                "no fallback-group in the file: the default lobby need not exist");
        // A named fallback group cannot be removed while it is named.
        assertEquals(Set.of("cloud.yml fallback-group UNKNOWN_GROUP"), codes(hub.withoutGroup("hub")));
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
    void corsOriginsAreChecked() {
        ApiConfig good = ApiConfig.defaults().withCorsOrigins(List.of("https://dashboard.example.org",
                "http://localhost:5173", "http://[::1]:8080"));
        assertEquals(Set.of(), codes(withCloud(valid.cloud().withApi(good))));
        ApiConfig bad = ApiConfig.defaults().withCorsOrigins(List.of("*", "https://example.org/", "HTTPS://example.org",
                "https://user@example.org", "ftp://example.org", "https://dashboard.example.org",
                "https://dashboard.example.org"));
        assertEquals(Set.of("cloud.yml api.cors-origins[0] INVALID_ORIGIN", "cloud.yml api.cors-origins[1] INVALID_ORIGIN",
                "cloud.yml api.cors-origins[2] INVALID_ORIGIN", "cloud.yml api.cors-origins[3] INVALID_ORIGIN",
                "cloud.yml api.cors-origins[4] INVALID_ORIGIN", "cloud.yml api.cors-origins[6] DUPLICATE_ENTRY"),
                codes(withCloud(valid.cloud().withApi(bad))));
        List<String> many = java.util.stream.IntStream.range(0, ApiConfig.MAX_CORS_ORIGINS + 1)
                .mapToObj(i -> "https://d" + i + ".example.org").toList();
        assertEquals(Set.of("cloud.yml api.cors-origins OUT_OF_RANGE"),
                codes(withCloud(valid.cloud().withApi(ApiConfig.defaults().withCorsOrigins(many)))));
    }

    @Test
    void setupCannotCompleteWithoutEula() {
        assertEquals(Set.of("cloud.yml setup-complete EULA_REQUIRED"), codes(withCloud(valid.cloud().withSetupComplete(true))));
        assertEquals(Set.of(), codes(withCloud(valid.cloud().withEulaAccepted(true).withSetupComplete(true))));
    }

    @Test
    void missingSectionsAreRequired() {
        CloudConfig empty = new CloudConfig(1, null, null, false, false, null, null, null, null, null, null, null);
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
