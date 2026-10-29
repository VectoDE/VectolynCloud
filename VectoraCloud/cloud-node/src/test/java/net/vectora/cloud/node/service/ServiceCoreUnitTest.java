package net.vectora.cloud.node.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import net.vectora.cloud.config.model.CloudConfig;
import net.vectora.cloud.config.model.CombatSystem;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.HeapConfig;
import net.vectora.cloud.config.model.JavaSelection;
import net.vectora.cloud.config.model.LegacyProxyConfig;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.model.NodeConfig;
import net.vectora.cloud.config.model.NodesConfig;
import net.vectora.cloud.config.model.PortRange;
import net.vectora.cloud.config.model.ProcessConfig;
import net.vectora.cloud.config.model.RestartConfig;
import net.vectora.cloud.config.model.ScalingConfig;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.model.ServerSoftware;
import net.vectora.cloud.config.model.TaskConfig;
import net.vectora.cloud.config.model.TaskMode;
import net.vectora.cloud.config.model.TimeSpan;
import net.vectora.cloud.config.model.WantedServers;
import net.vectora.cloud.config.secret.EnvFile;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.cloud.network.ServiceTokens;
import net.vectora.cloud.template.ServerPlatform;
import net.vectora.protocol.AuthToken;
import net.vectora.protocol.ServiceEnvironment;
import net.vectora.protocol.ServiceId;
import org.junit.jupiter.api.Test;

/** The parts of the service core that need no process: states, ids, ports, planning, tokens, command line. */
class ServiceCoreUnitTest {

    @Test
    void lifeCycleAllowsOnlyTheDocumentedSteps() {
        Map<ServiceState, Set<ServiceState>> allowed = Map.of(
                ServiceState.PREPARED, EnumSet.of(ServiceState.STARTING, ServiceState.STOPPED, ServiceState.CRASHED),
                ServiceState.STARTING, EnumSet.of(ServiceState.RUNNING, ServiceState.STOPPING, ServiceState.STOPPED,
                        ServiceState.CRASHED),
                ServiceState.RUNNING, EnumSet.of(ServiceState.STOPPING, ServiceState.STOPPED, ServiceState.CRASHED),
                ServiceState.STOPPING, EnumSet.of(ServiceState.STOPPED),
                ServiceState.STOPPED, EnumSet.of(ServiceState.PREPARED),
                ServiceState.CRASHED, EnumSet.of(ServiceState.PREPARED, ServiceState.STOPPED));
        for (ServiceState from : ServiceState.values()) {
            for (ServiceState to : ServiceState.values()) {
                assertEquals(allowed.get(from).contains(to), from.canBecome(to), from + " -> " + to);
            }
        }
        assertEquals(EnumSet.of(ServiceState.STARTING, ServiceState.RUNNING, ServiceState.STOPPING),
                EnumSet.copyOf(java.util.Arrays.stream(ServiceState.values()).filter(ServiceState::hasProcess).toList()));
    }

    @Test
    void serviceIdsFollowGroupDashIndex() {
        assertEquals(new ServiceId("lobby-1"), ServiceIds.of("lobby", 1));
        assertEquals(new ServiceId("bed_wars-9999"), ServiceIds.of("bed_wars", 9999));
        String longest = "a".repeat(32);
        assertEquals(37, ServiceIds.of(longest, 9999).value().length());
        assertEquals(OptionalInt.of(12), ServiceIds.index(new ServiceId("lobby-12"), "lobby"));
        assertEquals(OptionalInt.of(3), ServiceIds.index(new ServiceId("my-lobby-3"), "my-lobby"));
        assertEquals(OptionalInt.empty(), ServiceIds.index(new ServiceId("lobby-12"), "lob"));
        assertEquals(OptionalInt.empty(), ServiceIds.index(new ServiceId("lobby-01"), "lobby"));
        for (String bad : List.of("Lobby", "", "con", "a/b", "-x", "x".repeat(33))) {
            assertThrows(IllegalArgumentException.class, () -> ServiceIds.of(bad, 1), bad);
        }
        for (int bad : List.of(0, -1, 10_000)) {
            assertThrows(IllegalArgumentException.class, () -> ServiceIds.of("lobby", bad), "index " + bad);
        }
    }

    @Test
    void portsNeverCollideAndSkipReservedAndBusyPorts() throws ServiceException {
        Set<Integer> busy = Set.of(30002);
        PortAllocator ports = new PortAllocator(new PortRange(30000, 30005), List.of(30001), port -> !busy.contains(port));
        Set<Integer> handedOut = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            assertTrue(handedOut.add(ports.allocate(0)), "duplicate port");
        }
        assertEquals(Set.of(30000, 30003, 30004, 30005), handedOut);
        assertThrows(ServiceException.class, () -> ports.allocate(0), "range exhausted");
        ports.release(30004);
        assertEquals(30004, ports.allocate(30004), "a released port can be handed out again");

        PortAllocator fresh = new PortAllocator(new PortRange(30000, 30005), List.of(), port -> true);
        assertEquals(30003, fresh.allocate(30003), "preferred port is kept when free");
        assertEquals(30000, fresh.allocate(30003), "but never twice");
        assertEquals(30001, fresh.allocate(40000), "a preferred port outside the range is ignored");
        assertTrue(fresh.claim(25565));
        assertFalse(fresh.claim(25565));
    }

    @Test
    void portProbeSeesAPortThatIsInUse() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            PortAllocator.PortProbe probe = PortAllocator.PortProbe.binding("127.0.0.1");
            assertFalse(probe.isFree(socket.getLocalPort()));
        }
    }

    @Test
    void onlyWantedGroupsArePlannedAndIndexesCountPerGroup() {
        GroupConfig lobby = group(ServerKind.LOBBY.templateFolder(), ServerSoftware.PAPER);
        GroupConfig survival = group(ServerKind.SURVIVAL.templateFolder(), ServerSoftware.PAPER);
        GroupConfig custom = group("BedWars-Template", ServerSoftware.PAPER);
        GroupConfig database = group(ServerKind.DATABASE.templateFolder(), ServerSoftware.PAPER);
        CloudConfig cloud = CloudConfig.defaults().withServers(WantedServers.all().with(ServerKind.SURVIVAL, false));
        ConfigSnapshot snapshot = ConfigSnapshot.of(cloud, NodesConfig.defaults(),
                Map.of("lobby", lobby, "survival", survival, "bedwars", custom, "db", database),
                Map.of("lobby-a", task("lobby", TaskMode.STATIC, 2, List.of()),
                        "lobby-b", task("lobby", TaskMode.DYNAMIC, 1, List.of("node-1")),
                        "survival", task("survival", TaskMode.STATIC, 1, List.of()),
                        "bedwars", task("bedwars", TaskMode.DYNAMIC, 1, List.of()),
                        "db", task("db", TaskMode.STATIC, 1, List.of()),
                        "elsewhere", task("lobby", TaskMode.STATIC, 1, List.of("node-2"))));

        assertEquals(List.of(
                new ServicePlanner.Planned("bedwars", "bedwars", 1),
                new ServicePlanner.Planned("lobby-a", "lobby", 1),
                new ServicePlanner.Planned("lobby-a", "lobby", 2),
                new ServicePlanner.Planned("lobby-b", "lobby", 3)), ServicePlanner.plan(snapshot, "node-1"));

        assertTrue(ServicePlanner.isWanted(lobby, cloud.servers()));
        assertFalse(ServicePlanner.isWanted(survival, cloud.servers()));
        assertTrue(ServicePlanner.isWanted(custom, cloud.servers()), "own templates count as wanted");
        assertFalse(ServicePlanner.isWanted(database, WantedServers.all()), "the database is no service");
        assertEquals(Optional.of(ServerKind.LOBBY), ServicePlanner.kindOf(lobby));
        assertEquals(Optional.empty(), ServicePlanner.kindOf(custom));
    }

    @Test
    void workDirectoriesKeepStaticWorldsAndPutDynamicServicesIntoTemp() {
        Path home = Path.of("net").toAbsolutePath();
        GroupConfig lobby = group("Lobby-Server", ServerSoftware.PAPER);
        assertEquals(home.resolve("Lobby-Server").resolve("runtime"),
                ServicePlanner.workDirectory(home, lobby, TaskMode.STATIC, "lobby-1", 1));
        assertEquals(home.resolve("Lobby-Server").resolve("runtime-3"),
                ServicePlanner.workDirectory(home, lobby, TaskMode.STATIC, "lobby-3", 3));
        assertEquals(home.resolve("temp").resolve("services").resolve("lobby-2"),
                ServicePlanner.workDirectory(home, lobby, TaskMode.DYNAMIC, "lobby-2", 2));
    }

    @Test
    void backoffDoublesUpToTheLimit() {
        RestartConfig restart = new RestartConfig(true, 10, TimeSpan.parse("5s"), TimeSpan.parse("1m"));
        List<Long> seconds = new ArrayList<>();
        for (int attempt = 1; attempt <= 6; attempt++) {
            seconds.add(ServicePlanner.backoff(restart, attempt).getSeconds());
        }
        assertEquals(List.of(5L, 10L, 20L, 40L, 60L, 60L), seconds);
        assertEquals(Duration.ZERO, ServicePlanner.backoff(
                new RestartConfig(true, 3, TimeSpan.parse("0s"), TimeSpan.parse("0s")), 3));
        assertEquals(Duration.ofSeconds(300), ServicePlanner.backoff(RestartConfig.defaults(), 1000));
    }

    @Test
    void theLegacyGroupsAreWantedOnlyWithTheLegacyProxy() {
        GroupConfig legacyProxy = group(ServicePlanner.LEGACY_PROXY_TEMPLATE, ServerSoftware.VELOCITY);
        GroupConfig legacyServer = group("Legacy-Server", ServerSoftware.PANDASPIGOT);
        GroupConfig lobby = group("Lobby-Server", ServerSoftware.PAPER);
        CloudConfig off = CloudConfig.defaults();
        CloudConfig on = off.withLegacyProxy(new LegacyProxyConfig(true, 25564));
        assertTrue(ServicePlanner.isLegacy(legacyProxy));
        assertTrue(ServicePlanner.isLegacy(legacyServer));
        assertFalse(ServicePlanner.isLegacy(lobby));
        assertFalse(ServicePlanner.isWanted(legacyProxy, off), "legacy-proxy.enabled is false");
        assertFalse(ServicePlanner.isWanted(legacyServer, off));
        assertTrue(ServicePlanner.isWanted(legacyProxy, on));
        assertTrue(ServicePlanner.isWanted(legacyServer, on));
        assertTrue(ServicePlanner.isWanted(lobby, off), "other groups do not depend on it");
        assertTrue(spec(legacyProxy, 25564).isLegacyProxy());
        assertFalse(spec(group("Proxy", ServerSoftware.VELOCITY), 25565).isLegacyProxy());
    }

    @Test
    void commandLineHasHeapFlagsAndNoGuiButNoShell() {
        GroupConfig paper = group("Lobby-Server", ServerSoftware.PAPER)
                .withProcess(new ProcessConfig(List.of("-XX:+UseG1GC", "-Dx=a;b|c"), null, null, null));
        ServiceSpec spec = spec(paper, 30000);
        JavaRuntimeResolver.JavaRuntime java21 = new JavaRuntimeResolver.JavaRuntime(Path.of("/opt/jre 21/bin/java"), 21);
        assertEquals(List.of(java21.executable().toString(), "-Xms256M", "-Xmx1G", "-XX:+UseG1GC", "-Dx=a;b|c",
                        "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar", "paper.jar", "--nogui"),
                ServiceManager.command(java21, spec, "paper.jar"));
        JavaRuntimeResolver.JavaRuntime java8 = new JavaRuntimeResolver.JavaRuntime(Path.of("java"), 8);
        GroupConfig velocity = group("Proxy", ServerSoftware.VELOCITY);
        assertEquals(List.of("java", "-Xms256M", "-Xmx1G", "-jar", "velocity.jar"),
                ServiceManager.command(java8, spec(velocity, 25565), "velocity.jar"));
        // CraftBukkit 1.8 refuses "--nogui" as an unknown option and exits; it takes the bare word.
        List<String> legacy = ServiceManager.command(java21, spec(group("Legacy-Server", ServerSoftware.PANDASPIGOT),
                30001), "pandaspigot.jar");
        assertEquals(List.of("-jar", "pandaspigot.jar", "nogui"), legacy.subList(legacy.size() - 3, legacy.size()));
        assertEquals(ServerPlatform.PANDASPIGOT, ServiceManager.platformOf(group("Legacy-Server", ServerSoftware.PANDASPIGOT)));
        assertEquals(ServerPlatform.VELOCITY_LEGACY, ServiceManager.platformOf(
                group(ServicePlanner.LEGACY_PROXY_TEMPLATE, ServerSoftware.VELOCITY)));
        assertEquals(ServerPlatform.VELOCITY, ServiceManager.platformOf(group("Proxy", ServerSoftware.VELOCITY)));
        assertEquals(ServerPlatform.PAPER, ServiceManager.platformOf(group("Lobby-Server", ServerSoftware.PAPER)));
        assertEquals(ServerPlatform.FABRIC, ServiceManager.platformOf(group("Mod-Server", ServerSoftware.FABRIC)));
        assertEquals("fabric-server.jar", ServerSoftwareProvider.jarName(ServerSoftware.FABRIC));
    }

    @Test
    void environmentCarriesTheServiceButNoInheritedVectoraVariables() {
        AuthToken token = new ServiceTokens().issue(new ServiceId("lobby-1"));
        NodeConfig node = NodeConfig.defaults();
        Secret legacy = Secret.of("legacy-secret-0123456789abcdef");
        Map<String, String> env = ServiceManager.environment(spec(group("Lobby-Server", ServerSoftware.PAPER), 30007),
                ServerPlatform.PAPER, Optional.of(legacy), token, node.host(), node.port());
        assertEquals("lobby-1", env.get("VECTORA_SERVICE_ID"));
        assertEquals("lobby", env.get("VECTORA_SERVICE_GROUP"));
        assertEquals("30007", env.get("VECTORA_SERVICE_PORT"));
        assertEquals("PAPER", env.get(ServiceEnvironment.SERVICE_PLATFORM));
        assertEquals(token.value(), env.get(ServiceEnvironment.SERVICE_TOKEN));
        assertEquals("127.0.0.1", env.get("VECTORA_CLOUD_HOST"));
        assertEquals("7010", env.get("VECTORA_CLOUD_PORT"));
        assertFalse(env.containsKey(ServiceEnvironment.LEGACY_FORWARDING_SECRET),
                "a Paper server never gets the legacy secret, even if it is passed by mistake");
        Set<String> expected = Set.of("VECTORA_SERVICE_ID", "VECTORA_SERVICE_GROUP", "VECTORA_SERVICE_TASK",
                "VECTORA_SERVICE_PORT", "VECTORA_SERVICE_TOKEN", "VECTORA_CLOUD_HOST", "VECTORA_CLOUD_PORT",
                "VECTORA_SERVICE_PLATFORM");
        for (String key : env.keySet()) {
            if (key.toUpperCase(java.util.Locale.ROOT).startsWith("VECTORA_")) {
                assertTrue(expected.contains(key), "inherited " + key);
            }
        }

        // Counter-check: the PandaSpigot server gets it, under the protocol's constant.
        Map<String, String> panda = ServiceManager.environment(spec(group("Legacy-Server", ServerSoftware.PANDASPIGOT),
                30008), ServerPlatform.PANDASPIGOT, Optional.of(legacy), token, node.host(), node.port());
        assertEquals("PANDASPIGOT", panda.get(ServiceEnvironment.SERVICE_PLATFORM));
        assertEquals(legacy.reveal(), panda.get(ServiceEnvironment.LEGACY_FORWARDING_SECRET));
        assertEquals("VECTORA_SERVICE_PLATFORM", ServiceEnvironment.SERVICE_PLATFORM);
        assertEquals("VECTORA_LEGACY_FORWARDING_SECRET", ServiceEnvironment.LEGACY_FORWARDING_SECRET);
        for (ServerPlatform platform : List.of(ServerPlatform.VELOCITY, ServerPlatform.VELOCITY_LEGACY,
                ServerPlatform.FABRIC)) {
            Map<String, String> other = ServiceManager.environment(spec(group("Proxy", ServerSoftware.VELOCITY), 25565),
                    platform, Optional.of(legacy), token, node.host(), node.port());
            assertEquals(platform.name(), other.get(ServiceEnvironment.SERVICE_PLATFORM));
            assertFalse(other.containsKey(ServiceEnvironment.LEGACY_FORWARDING_SECRET), platform.name());
            assertFalse(other.containsValue(legacy.reveal()), platform.name());
        }
    }

    @Test
    void eachPlatformSeesOnlyTheSecretsItNeeds() throws Exception {
        EnvFile env = env(Map.of(
                ServiceSecrets.FORWARDING, "modern-forwarding-0123456789",
                ServiceSecrets.LEGACY_FORWARDING, "legacy-forwarding-0123456789",
                ServiceSecrets.DB_PASSWORD, "db-password-0123456789ab",
                ServiceSecrets.DB_ROOT_PASSWORD, "db-root-password-0123456789",
                ServiceSecrets.MANAGEMENT, "management-secret-0123456789",
                ServiceSecrets.FLOODGATE, "AAAAAAAAAAAAAAAAAAAAAA==",
                "VECTORA_OPERATOR_EXTRA", "something-the-operator-added"));
        Map<ServerPlatform, Set<String>> expected = Map.of(
                ServerPlatform.VELOCITY, Set.of(ServiceSecrets.FORWARDING, ServiceSecrets.FLOODGATE,
                        ServiceSecrets.DB_PASSWORD),
                ServerPlatform.VELOCITY_LEGACY, Set.of(ServiceSecrets.LEGACY_FORWARDING),
                ServerPlatform.PAPER, Set.of(ServiceSecrets.FORWARDING, ServiceSecrets.MANAGEMENT,
                        ServiceSecrets.DB_PASSWORD, ServiceSecrets.FLOODGATE),
                ServerPlatform.FABRIC, Set.of(ServiceSecrets.FORWARDING, ServiceSecrets.MANAGEMENT,
                        ServiceSecrets.DB_PASSWORD, ServiceSecrets.FLOODGATE),
                ServerPlatform.PANDASPIGOT, Set.of(),
                ServerPlatform.NONE, Set.of(ServiceSecrets.DB_PASSWORD));
        for (ServerPlatform platform : ServerPlatform.values()) {
            Map<String, String> values = ServiceSecrets.placeholders(platform, env);
            assertEquals(expected.get(platform), values.keySet(), platform.name());
            assertFalse(values.containsKey(ServiceSecrets.DB_ROOT_PASSWORD), platform.name());
            assertFalse(values.containsValue("db-root-password-0123456789"), platform.name());
            assertFalse(values.containsKey("VECTORA_OPERATOR_EXTRA"), "not a placeholder: " + platform);
            for (String key : values.keySet()) {
                assertEquals(env.get(key).orElseThrow().reveal(), values.get(key));
            }
        }
        assertFalse(ServiceSecrets.allPlaceholders().contains(ServiceSecrets.DB_ROOT_PASSWORD));
        assertEquals(Optional.empty(), ServiceSecrets.legacyForwardingFor(ServerPlatform.PAPER, env));
        assertEquals(Optional.empty(), ServiceSecrets.legacyForwardingFor(ServerPlatform.VELOCITY_LEGACY, env));
        assertEquals("legacy-forwarding-0123456789",
                ServiceSecrets.legacyForwardingFor(ServerPlatform.PANDASPIGOT, env).orElseThrow().reveal());
    }

    @Test
    void aMissingOrSharedSecretFailsClearly() throws Exception {
        EnvFile noFloodgate = env(Map.of(ServiceSecrets.FORWARDING, "modern-forwarding-0123456789",
                ServiceSecrets.DB_PASSWORD, "db-password-0123456789ab",
                ServiceSecrets.MANAGEMENT, "management-secret-0123456789",
                ServiceSecrets.FLOODGATE, ""));
        ServiceException missing = assertThrows(ServiceException.class,
                () -> ServiceSecrets.placeholders(ServerPlatform.PAPER, noFloodgate));
        assertTrue(missing.getMessage().contains(ServiceSecrets.FLOODGATE), missing.getMessage());
        assertTrue(missing.getMessage().contains("PAPER"), missing.getMessage());
        assertFalse(missing.getMessage().contains("modern-forwarding-0123456789"), "no value in the message");
        // Counter-check: a platform that does not need the key is fine without it.
        assertEquals(Set.of(ServiceSecrets.DB_PASSWORD),
                ServiceSecrets.placeholders(ServerPlatform.NONE, noFloodgate).keySet());
        // A Paper template without Floodgate's folder neither sees nor needs the key.
        assertEquals(Set.of(ServiceSecrets.FORWARDING, ServiceSecrets.MANAGEMENT, ServiceSecrets.DB_PASSWORD),
                ServiceSecrets.placeholders(ServerPlatform.PAPER, noFloodgate, false).keySet());
        assertThrows(ServiceException.class, () -> ServiceSecrets.placeholders(ServerPlatform.VELOCITY_LEGACY,
                noFloodgate), "no legacy secret at all");
        assertThrows(ServiceException.class, () -> ServiceSecrets.legacyForwardingFor(ServerPlatform.PANDASPIGOT,
                noFloodgate));

        EnvFile shared = env(Map.of(ServiceSecrets.FORWARDING, "same-forwarding-0123456789",
                ServiceSecrets.LEGACY_FORWARDING, "same-forwarding-0123456789"));
        ServiceException same = assertThrows(ServiceException.class,
                () -> ServiceSecrets.placeholders(ServerPlatform.VELOCITY_LEGACY, shared));
        assertTrue(same.getMessage().contains("equals"), same.getMessage());
        assertFalse(same.getMessage().contains("same-forwarding-0123456789"));
        assertThrows(ServiceException.class, () -> ServiceSecrets.legacyForwardingFor(ServerPlatform.PANDASPIGOT,
                shared));
    }

    /**
     * The "wanted" rule as a matrix: every kind of group against every switch. The expected value is
     * written out per row instead of being computed with the code under test.
     */
    @Test
    void wantedFollowsTheGroupSwitchTheServerSwitchAndTheLegacyProxy() {
        record Row(String name, GroupConfig group, ServerKind serverSwitch, boolean needsLegacyProxy) {
        }
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("proxy", group("Proxy", ServerSoftware.VELOCITY), ServerKind.PROXY, false));
        rows.add(new Row("lobby", group("Lobby-Server", ServerSoftware.PAPER), ServerKind.LOBBY, false));
        rows.add(new Row("survival", group("Survival-Server", ServerSoftware.PAPER), ServerKind.SURVIVAL, false));
        rows.add(new Row("creative", group("Creative-Server", ServerSoftware.PAPER), ServerKind.CREATIVE, false));
        rows.add(new Row("mod", group("Mod-Server", ServerSoftware.FABRIC), ServerKind.MOD, false));
        rows.add(new Row("end", group("End-Server", ServerSoftware.PAPER), ServerKind.END, false));
        rows.add(new Row("bedwars", group("Minigame-Server", ServerSoftware.PAPER), ServerKind.MINIGAME, false));
        rows.add(new Row("legacy-server", group("Legacy-Server", ServerSoftware.PANDASPIGOT),
                ServerKind.LEGACY_SERVER, true));
        rows.add(new Row("panda-own", group("Panda-Own", ServerSoftware.PANDASPIGOT), ServerKind.LEGACY_SERVER, true));
        rows.add(new Row("legacy-proxy", group(ServicePlanner.LEGACY_PROXY_TEMPLATE, ServerSoftware.VELOCITY), null,
                true));
        rows.add(new Row("custom", group("BedWars-Template", ServerSoftware.PAPER), null, false));

        LegacyProxyConfig legacyOn = new LegacyProxyConfig(true, 25564);
        LegacyProxyConfig legacyOff = new LegacyProxyConfig(false, 25564);
        CloudConfig allOn = CloudConfig.defaults().withServers(WantedServers.all()).withLegacyProxy(legacyOn);
        for (Row row : rows) {
            // Everything on: every row runs. This is the counter-check for the rows below.
            assertTrue(ServicePlanner.isWanted(row.group(), allOn), row.name() + " with everything on");
            // Its own group switch off: never, whatever else is on.
            assertFalse(ServicePlanner.isWanted(row.group().withWanted(false), allOn), row.name() + " wanted: false");
            assertTrue(ServicePlanner.isWanted(row.group().withWanted(true), allOn), row.name() + " wanted: true");
            // The legacy proxy off: only the 1.8.8 part stops.
            assertEquals(!row.needsLegacyProxy(), ServicePlanner.isWanted(row.group(), allOn.withLegacyProxy(legacyOff)),
                    row.name() + " with legacy-proxy.enabled false");
            // Each server switch off on its own: only the rows that follow that switch stop.
            for (ServerKind kind : ServerKind.values()) {
                CloudConfig oneOff = allOn.withServers(WantedServers.all().with(kind, false));
                boolean expected = kind != row.serverSwitch();
                assertEquals(expected, ServicePlanner.isWanted(row.group(), oneOff),
                        row.name() + " with servers." + kind.key() + " false");
            }
            // Nothing on: only the operator's own group, which no switch covers.
            CloudConfig none = allOn.withServers(WantedServers.none());
            assertEquals(row.serverSwitch() == null, ServicePlanner.isWanted(row.group(), none),
                    row.name() + " with every server off");
        }
        assertFalse(ServicePlanner.isWanted(group("Database", ServerSoftware.PAPER), allOn), "the database is no service");
        assertEquals(Optional.of(ServerKind.LEGACY_SERVER), ServicePlanner.switchOf(group("Panda-Own",
                ServerSoftware.PANDASPIGOT)));
        assertEquals(Optional.empty(), ServicePlanner.switchOf(group("BedWars-Template", ServerSoftware.PAPER)));
        assertEquals("wanted: false in its group file", ServicePlanner.unwantedBecause(
                group("Lobby-Server", ServerSoftware.PAPER).withWanted(false), allOn));
        assertEquals("cloud.yml servers.legacy-server", ServicePlanner.unwantedBecause(
                group("Legacy-Server", ServerSoftware.PANDASPIGOT),
                allOn.withServers(WantedServers.all().with(ServerKind.LEGACY_SERVER, false))));
        assertEquals("cloud.yml legacy-proxy.enabled", ServicePlanner.unwantedBecause(
                group("Legacy-Server", ServerSoftware.PANDASPIGOT), allOn.withLegacyProxy(legacyOff)));
    }

    @Test
    void thePlanLeavesOutEveryUnwantedGroup() {
        GroupConfig lobby = group("Lobby-Server", ServerSoftware.PAPER);
        GroupConfig bedwars = group("Minigame-Server", ServerSoftware.PAPER).withWanted(false);
        GroupConfig skywars = group("Minigame-Server", ServerSoftware.PAPER);
        GroupConfig legacy = group("Legacy-Server", ServerSoftware.PANDASPIGOT);
        GroupConfig legacyProxy = group(ServicePlanner.LEGACY_PROXY_TEMPLATE, ServerSoftware.VELOCITY);
        Map<String, GroupConfig> groups = Map.of("lobby", lobby, "bedwars", bedwars, "skywars", skywars,
                "legacy", legacy, "legacy-proxy", legacyProxy);
        Map<String, TaskConfig> tasks = Map.of("lobby", task("lobby", TaskMode.STATIC, 1, List.of()),
                "bedwars", task("bedwars", TaskMode.DYNAMIC, 1, List.of()),
                "skywars", task("skywars", TaskMode.DYNAMIC, 1, List.of()),
                "legacy", task("legacy", TaskMode.STATIC, 1, List.of()),
                "legacy-proxy", task("legacy-proxy", TaskMode.STATIC, 1, List.of()));
        CloudConfig cloud = CloudConfig.defaults().withServers(WantedServers.all().with(ServerKind.LEGACY_SERVER, false))
                .withLegacyProxy(new LegacyProxyConfig(true, 25564));
        List<String> planned = ServicePlanner.plan(ConfigSnapshot.of(cloud, NodesConfig.defaults(), groups, tasks),
                "node-1").stream().map(ServicePlanner.Planned::group).toList();
        assertEquals(List.of("legacy-proxy", "lobby", "skywars"), planned);

        // Counter-check: with both switched on, both are planned.
        CloudConfig on = cloud.withServers(WantedServers.all());
        Map<String, GroupConfig> bothOn = new java.util.HashMap<>(groups);
        bothOn.put("bedwars", bedwars.withWanted(true));
        assertEquals(List.of("bedwars", "legacy", "legacy-proxy", "lobby", "skywars"),
                ServicePlanner.plan(ConfigSnapshot.of(on, NodesConfig.defaults(), bothOn, tasks), "node-1").stream()
                        .map(ServicePlanner.Planned::group).toList());
    }

    private static EnvFile env(Map<String, String> values) throws Exception {
        StringBuilder text = new StringBuilder();
        new java.util.TreeMap<>(values).forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        return net.vectora.cloud.config.secret.EnvFileParser.parse(text.toString());
    }

    @Test
    void currentJavaRuntimeIsTheTestJvm() throws Exception {
        JavaRuntimeResolver.JavaRuntime runtime = JavaRuntimeResolver.current().resolve("lobby",
                group("Lobby-Server", ServerSoftware.PAPER));
        assertEquals(Runtime.version().feature(), runtime.featureVersion());
        assertTrue(java.nio.file.Files.isRegularFile(runtime.executable()));
        assertThrows(IllegalArgumentException.class, () -> new JavaRuntimeResolver.JavaRuntime(Path.of("java"), -1));
    }

    @Test
    void existingJarProviderRequiresTheJar(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        ServiceSpec spec = spec(group("Lobby-Server", ServerSoftware.PAPER), 30000);
        ServiceException missing = new ServiceException("x");
        java.io.IOException e = assertThrows(java.io.IOException.class,
                () -> ServerSoftwareProvider.existingJar().provide(spec, dir));
        assertTrue(e.getMessage().contains("paper.jar"), e.getMessage());
        java.nio.file.Files.writeString(dir.resolve("paper.jar"), "jar");
        assertEquals("paper.jar", ServerSoftwareProvider.existingJar().provide(spec, dir));
        assertEquals("x", missing.getMessage());
    }

    static GroupConfig group(String template, ServerSoftware software) {
        return new GroupConfig(GroupConfig.SCHEMA_VERSION, template, software, "26.3", JavaSelection.AUTO,
                new HeapConfig(MemorySize.parse("256M"), MemorySize.parse("1G")), List.of(), CombatSystem.MODERN);
    }

    static TaskConfig task(String group, TaskMode mode, int min, List<String> nodes) {
        return new TaskConfig(TaskConfig.SCHEMA_VERSION, group, mode,
                new ScalingConfig(min, Math.max(min, 1), 50, 80, TimeSpan.parse("5m"), TimeSpan.parse("60s")), nodes);
    }

    static ServiceSpec spec(GroupConfig group, int port) {
        Path home = Path.of("home").toAbsolutePath();
        String name = group.software() == ServerSoftware.VELOCITY ? "proxy" : "lobby";
        return new ServiceSpec(ServiceIds.of(name, 1), name, name, 1, group, TaskMode.STATIC,
                ServicePlanner.kindOf(group), home.resolve(group.template()).resolve("template"),
                home.resolve(group.template()).resolve("runtime"), port);
    }
}
