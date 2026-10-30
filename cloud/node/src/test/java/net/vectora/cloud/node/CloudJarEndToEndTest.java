package net.vectora.cloud.node;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.vectora.cloud.download.cache.ArtifactCache;
import net.vectora.cloud.download.lock.LockedArtifact;
import net.vectora.cloud.download.lock.SoftwareLockStore;
import net.vectora.cloud.node.fake.FakeServerJar;
import net.vectora.cloud.node.service.ServiceState;
import net.vectora.cloud.node.software.OfflineSoftware;
import net.vectora.cloud.node.state.ServiceRecord;
import net.vectora.cloud.node.state.ServiceStateStore;
import net.vectora.cloud.node.state.ServiceStates;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real {@code VectoraCloud.jar} in an empty folder, as a separate process, without network:
 * launcher, embedded libraries, setup file, built-in templates, software from a pre-seeded
 * {@code config/software.lock.yml}, the home layout and start output, bridges, Java selection, every default server of the network, the
 * proxies' server lists and the clean shutdown.
 *
 * <p>The server software is {@link FakeServerJar#writeConnected}, which logs in to the cloud channel
 * like a bridge. Real Paper, Velocity and PandaSpigot and real downloads are a manual or scheduled
 * run, see {@code docs/cloud/node.md#end-to-end-test}.
 *
 * <p>Run with {@code ./gradlew :cloud:node:e2eTest}; the build passes the jar and a
 * Java 21 and 25 launcher in {@code vectora.e2e.*}.
 */
@Tag("e2e")
class CloudJarEndToEndTest {

    /**
     * {@code -Pe2eDatabase=managed}: the cloud downloads and runs its own MariaDB instead of H2
     * (needs internet access; {@code managed-mariadb.yml} runs it).
     */
    private static final boolean MANAGED = "managed".equals(System.getProperty("vectora.e2e.database", "h2"));

    /** Downloading and initialising MariaDB takes minutes on a CI runner, the fake servers seconds. */
    private static final Duration START_TIMEOUT = Duration.ofMinutes(MANAGED ? 15 : 3);

    /** Services of the setup with every server switched on; minigames scale from one. */
    private static final List<String> SERVICES = List.of("proxy-1", "lobby-1", "survival-1", "creative-1", "mod-1",
            "end-1", "minigame-1", "legacy-proxy-1", "legacy-server-1");

    @TempDir
    Path dir;

    @Test
    void theJarBringsUpTheWholeNetworkInAnEmptyFolderWithoutNetwork() throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Map<String, List<String>> addons = OfflineSoftware.seed(home, FakeServerJar.writeConnected(dir.resolve("fake.jar")));
        byte[] lockBefore = Files.readAllBytes(home.resolve("config/software.lock.yml"));
        Map<String, LockedArtifact> seeded = SoftwareLockStore.inHome(home).read().entries();
        Path output = dir.resolve("cloud.out");
        Process cloud = launch(home, output, """
                servers:
                  proxy: true
                  lobby: true
                  survival: true
                  creative: true
                  mod: true
                  end: true
                  minigame: true
                  legacy-proxy: true
                  legacy-server: true
                memory:
                  proxy: 256M
                  lobby: 256M
                  survival: 256M
                  creative: 256M
                  mod: 256M
                  end: 256M
                  minigame: 256M
                  legacy-proxy: 256M
                  legacy-server: 256M
                """);
        try {
            await("every service running", () -> allRunning(home, SERVICES), cloud, output, START_TIMEOUT);
            Map<String, ServiceRecord> running = states(home);

            // The setup ran from the file, the templates came out of the jar.
            String cloudYml = Files.readString(home.resolve("config/cloud.yml"));
            assertTrue(cloudYml.contains("setup-complete: true"), cloudYml);
            assertTrue(Files.isRegularFile(home.resolve("local/templates/Proxy/default/velocity.toml")));
            assertTrue(Files.isRegularFile(home.resolve("local/templates/Legacy-Server/default/pandaspigot.yml")));
            assertFalse(Files.exists(home.resolve("local/templates/Database")), "the managed database has no template");

            // The home layout of docs/cloud/architecture.md#cloud-home, nothing else at the top.
            try (Stream<Path> top = Files.list(home)) {
                List<String> names = top.map(p -> p.getFileName().toString()).sorted().toList();
                assertTrue(List.of("backups", "config", "database", "local", "logs", "secrets", "state", "storage",
                        "temp").containsAll(names), names.toString());
            }
            assertEquals("2", Files.readString(home.resolve("state/home-layout")).strip());
            assertTrue(Files.isRegularFile(home.resolve("logs/cloud.log")), "the cloud writes its own log");
            String console = read(output);
            assertTrue(console.contains("VectoraCloud "), "the banner names the version: " + tail(output));
            for (String step : List.of("[1/5] Configuration", "[2/5] Database", "[3/5] Software", "[4/5] Templates",
                    "[5/5] Services")) {
                assertTrue(console.contains(step), step + ": " + tail(output));
            }
            // The launcher's root warning box before the node starts is not a log line.
            assertFalse(console.contains("INFORMATION") || console.contains("WARNUNG") || console.contains("\nWARNING ")
                            || console.contains("\nINFO: ") || console.contains("\nSEVERE"),
                    "one log format, no localized java.util.logging lines: " + tail(output));

            // Software: the lock was replayed, nothing was downloaded, nothing half-written is left.
            if (MANAGED) {
                // The MariaDB server is the one real download; every seeded entry stays as it was.
                Map<String, LockedArtifact> after = SoftwareLockStore.inHome(home).read().entries();
                assertTrue(after.entrySet().containsAll(seeded.entrySet()), "the seeded entries are unchanged");
                after.keySet().stream().filter(name -> !seeded.containsKey(name))
                        .forEach(name -> assertTrue(name.contains("mariadb"), "only MariaDB was added: " + name));
                assertTrue(read(output).contains("Database ready: mariadb"), tail(output));
            } else {
                assertArrayEquals(lockBefore, Files.readAllBytes(home.resolve("config/software.lock.yml")),
                        "software.lock.yml unchanged");
                assertFalse(read(output).contains("Downloaded "), "no download happened");
                assertTrue(read(output).contains("Database ready: h2"), tail(output));
            }
            try (Stream<Path> files = Files.list(home.resolve("storage/artifacts"))) {
                assertFalse(files.anyMatch(f -> f.getFileName().toString().endsWith(ArtifactCache.PART_SUFFIX)));
            }

            // Each server got its plugins from the catalog and the bridge of its platform.
            Path services = home.resolve("local/services");
            assertManaged(services.resolve("proxy-1/plugins"), addons.get("Proxy"), "VectoraCloudBridgeVelocity-");
            assertManaged(services.resolve("legacy-proxy-1/plugins"), addons.get("Legacy-Proxy"), "VectoraCloudBridgeVelocity-");
            assertManaged(services.resolve("lobby-1/plugins"), addons.get("Lobby-Server"), "VectoraCloudBridgePaper-");
            assertManaged(services.resolve("legacy-server-1/plugins"), addons.get("Legacy-Server"), "VectoraCloudBridgeLegacy-");
            assertManaged(services.resolve("mod-1/mods"), addons.get("Mod-Server"), null);
            assertTrue(Files.isRegularFile(services.resolve("proxy-1/velocity.jar")));
            assertTrue(Files.isRegularFile(services.resolve("legacy-server-1/pandaspigot.jar")));
            assertTrue(Files.isRegularFile(services.resolve("lobby-1/plugins/floodgate/key.pem")));
            assertFalse(Files.exists(services.resolve("legacy-server-1/plugins/floodgate/key.pem")),
                    "no Floodgate key where the template has no Floodgate");

            // Java: 21 for PandaSpigot, 25 for everything else.
            Path logs = home.resolve("logs/services");
            await("Java lines", () -> read(logs.resolve("legacy-server-1.log")).contains("[fake] java ")
                    && read(logs.resolve("lobby-1.log")).contains("[fake] java "), cloud, output, START_TIMEOUT);
            assertTrue(read(logs.resolve("legacy-server-1.log")).contains("[fake] java 21"), "PandaSpigot on Java 21");
            assertTrue(read(logs.resolve("lobby-1.log")).contains("[fake] java 25"), "Paper on Java 25");
            assertTrue(read(logs.resolve("proxy-1.log")).contains("[fake] java 25"), "Velocity on Java 25");

            // The main proxy knows every modern backend; the legacy proxy only the 1.8.8 server.
            for (String backend : List.of("lobby-1", "survival-1", "creative-1", "mod-1", "end-1", "minigame-1")) {
                ServiceRecord record = running.get(backend);
                Pattern line = Pattern.compile("\\[fake] register \\S+ 127\\.0\\.0\\.1:" + record.port()
                        + " group=" + Pattern.quote(record.group()) + " legacy=false");
                await("proxy knows " + backend, () -> line.matcher(read(logs.resolve("proxy-1.log"))).find(), cloud, output,
                        START_TIMEOUT);
            }
            ServiceRecord legacy = running.get("legacy-server-1");
            Pattern legacyLine = Pattern.compile("\\[fake] register \\S+ 127\\.0\\.0\\.1:" + legacy.port()
                    + " group=legacy-server legacy=true");
            await("legacy proxy knows the 1.8.8 server",
                    () -> legacyLine.matcher(read(logs.resolve("legacy-proxy-1.log"))).find(), cloud, output, START_TIMEOUT);
            assertFalse(read(logs.resolve("proxy-1.log")).contains("group=legacy-server"), "the 1.8.8 server is not on the main proxy");
            assertFalse(read(logs.resolve("legacy-proxy-1.log")).contains("group=lobby"), "the lobby is not on the legacy proxy");

            // A clean shutdown: every server gets its stop command, the cloud ends with 0.
            shutdown(cloud, output);
            Map<String, ServiceRecord> after = states(home);
            for (String id : SERVICES) {
                if (id.equals("minigame-1")) {
                    assertFalse(after.containsKey(id), "a dynamic service is forgotten once it stopped");
                    continue;
                }
                assertNotNull(after.get(id), id);
                assertEquals(ServiceState.STOPPED, after.get(id).state(), id);
            }
            assertTrue(Files.exists(services.resolve("lobby-1/stopped-cleanly")), "lobby stopped with its command");
            assertTrue(Files.exists(services.resolve("proxy-1/stopped-cleanly")), "proxy stopped with its command");
        } finally {
            end(cloud);
        }
    }

    /**
     * The same start with the real downloads and the real Velocity, Paper 26.3 and PandaSpigot 1.8.8:
     * the main proxy with the lobby, the legacy proxy with the 1.8.8 server. Needs internet access
     * and a few GB of memory; {@code ./gradlew :cloud:node:e2eLiveTest} (weekly in
     * {@code cloud-e2e-live.yml}).
     */
    @Test
    @Tag("e2e-live")
    void withRealDownloadsTheRealServersStartAndTheProxiesKnowTheirBackends() throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Path output = dir.resolve("cloud.out");
        Process cloud = launch(home, output, """
                servers:
                  proxy: true
                  lobby: true
                  survival: false
                  creative: false
                  mod: false
                  end: false
                  minigame: false
                  legacy-proxy: true
                  legacy-server: true
                memory:
                  proxy: 512M
                  lobby: 1G
                  legacy-proxy: 512M
                  legacy-server: 512M
                """);
        List<String> services = List.of("proxy-1", "lobby-1", "legacy-proxy-1", "legacy-server-1");
        try {
            // RUNNING comes only from the bridge's ServiceReady on the cloud channel.
            await("every service running", () -> allRunning(home, services), cloud, output, Duration.ofMinutes(15));
            Map<String, ServiceRecord> running = states(home);
            assertTrue(Files.isRegularFile(home.resolve("local/services/proxy-1/velocity.jar")));
            assertTrue(Files.isRegularFile(home.resolve("local/services/lobby-1/paper.jar")));
            assertTrue(Files.isRegularFile(home.resolve("local/services/legacy-server-1/pandaspigot.jar")));
            assertTrue(read(home.resolve("config/software.lock.yml")).contains("velocity"), "the downloads are pinned");
            assertTrue(read(output).contains("Downloaded "), "the software was downloaded");

            Path logs = home.resolve("logs/services");
            Pattern lobby = Pattern.compile("Server \\S+ \\(127\\.0\\.0\\.1:" + running.get("lobby-1").port()
                    + "\\) from VectoraCloud");
            await("proxy knows the lobby", () -> lobby.matcher(read(logs.resolve("proxy-1.log"))).find(), cloud, output,
                    START_TIMEOUT);
            Pattern legacy = Pattern.compile("Server \\S+ \\(127\\.0\\.0\\.1:" + running.get("legacy-server-1").port()
                    + "\\) from VectoraCloud");
            await("legacy proxy knows the 1.8.8 server",
                    () -> legacy.matcher(read(logs.resolve("legacy-proxy-1.log"))).find(), cloud, output, START_TIMEOUT);

            shutdown(cloud, output);
            for (String id : services) {
                assertEquals(ServiceState.STOPPED, states(home).get(id).state(), id);
            }
        } finally {
            end(cloud);
        }
    }

    /**
     * Starts {@code VectoraCloud.jar} on Java 25 in {@code home} with a setup file: EULA, H2, every
     * port free, and the given {@code servers} and {@code memory} sections. The servers see exactly a
     * Java 21 and a Java 25 on the {@code PATH}.
     */
    private Process launch(Path home, Path output, String serversAndMemory) throws IOException {
        Path jar = Path.of(property("vectora.e2e.cloudJar"));
        Path java25 = Path.of(property("vectora.e2e.java25"));
        Path java21 = Path.of(property("vectora.e2e.java21"));
        int[] ports = freePorts(4);
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/nodes.yml"), """
                schema-version: 1
                nodes:
                  - id: node-1
                    host: 127.0.0.1
                    port: %d
                    service-ports:
                      first: 30000
                      last: 30999
                """.formatted(ports[0]), StandardCharsets.UTF_8);
        Path setup = dir.resolve("setup.yml");
        Files.writeString(setup, """
                schema-version: 1
                eula: true
                language: en
                network-name: Vectora E2E
                network:
                  bind: 127.0.0.1
                  java-port: %d
                  bedrock-port: %d
                  legacy-port: %d
                database:
                  mode: %s
                """.formatted(ports[1], ports[2], ports[3], MANAGED ? "managed" : "h2") + serversAndMemory,
                StandardCharsets.UTF_8);
        List<String> command = new ArrayList<>(List.of(java25.toString(), "-jar", jar.toString(), "--home", home.toString(),
                "--non-interactive", "--setup-file", setup.toString(), "--lang", "en"));
        if (Boolean.getBoolean("vectora.e2e.allowRoot")) {
            // Only for a developer shell that runs elevated; CI runs as a normal user.
            command.add("--allow-root");
        }
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(home.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile());
        builder.environment().put("PATH", java25.getParent() + java.io.File.pathSeparator + java21.getParent()
                + java.io.File.pathSeparator + System.getenv().getOrDefault("PATH", ""));
        builder.environment().put("JAVA_HOME", java25.getParent().getParent().toString());
        Process cloud = builder.start();
        // A test JVM that is ended from outside (cancelled build) never reaches end(), and the cloud
        // keeps running when its input ends (docs/cloud/console.md). The hook ends what this test
        // started, the cloud and its servers, and nothing else.
        Thread hook = new Thread(() -> destroyTree(cloud), "e2e-cloud-cleanup");
        Runtime.getRuntime().addShutdownHook(hook);
        cloud.onExit().thenRun(() -> {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // The JVM is shutting down already; the hook runs and finds nothing alive.
            }
        });
        return cloud;
    }

    private static void destroyTree(Process cloud) {
        List<ProcessHandle> children = new ArrayList<>(cloud.toHandle().descendants().toList());
        cloud.destroyForcibly();
        children.forEach(ProcessHandle::destroyForcibly);
    }

    private static boolean allRunning(Path home, List<String> ids) {
        Map<String, ServiceRecord> states = states(home);
        return ids.stream().allMatch(id -> states.containsKey(id) && states.get(id).state() == ServiceState.RUNNING);
    }

    /** {@code shutdown} on the console; the cloud must end with 0 within two minutes. */
    private static void shutdown(Process cloud, Path output) throws Exception {
        try (OutputStream in = cloud.getOutputStream()) {
            in.write("shutdown\n".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(cloud.waitFor(2, TimeUnit.MINUTES), "the cloud ends after shutdown:\n" + tail(output));
        assertEquals(0, cloud.exitValue(), tail(output));
    }

    private static void assertManaged(Path folder, List<String> addons, String bridgePrefix) throws IOException {
        assertNotNull(addons, folder.toString());
        Set<String> listed = new HashSet<>(Files.readAllLines(folder.resolve(".vectora-managed"), StandardCharsets.UTF_8));
        listed.removeIf(line -> line.isBlank() || line.startsWith("#"));
        Set<String> expected = new HashSet<>(addons);
        if (bridgePrefix != null) {
            Optional<String> bridge = listed.stream().filter(name -> name.startsWith(bridgePrefix)).findFirst();
            assertTrue(bridge.isPresent(), folder + " has its bridge: " + listed);
            expected.add(bridge.get());
        } else {
            assertTrue(listed.stream().noneMatch(name -> name.startsWith("VectoraCloudBridge")), folder + ": " + listed);
        }
        assertEquals(expected, listed, folder.toString());
        for (String name : listed) {
            assertTrue(Files.isRegularFile(folder.resolve(name)), folder + "/" + name);
        }
    }

    private static Map<String, ServiceRecord> states(Path home) {
        try {
            Optional<ServiceStates> states = new ServiceStateStore(home).load();
            Map<String, ServiceRecord> byId = new LinkedHashMap<>();
            states.ifPresent(value -> value.services().forEach(record -> byId.put(record.id(), record)));
            return byId;
        } catch (Exception e) {
            // Read while the node replaces it; the next poll sees the new file.
            return Map.of();
        }
    }

    private static void await(String what, BooleanSupplier condition, Process cloud, Path output, Duration timeout)
            throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            if (!cloud.isAlive()) {
                throw new AssertionError("the cloud ended (" + cloud.exitValue() + ") before: " + what + "\n" + tail(output));
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("timed out waiting for: " + what + "\n" + tail(output));
            }
            Thread.sleep(250);
        }
    }

    /** Asks the cloud to stop; only if it does not, ends it and the servers it started. */
    private static void end(Process cloud) throws InterruptedException {
        if (!cloud.isAlive()) {
            return;
        }
        try (OutputStream in = cloud.getOutputStream()) {
            in.write("shutdown\n".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // stdin is closed already.
        }
        if (cloud.waitFor(1, TimeUnit.MINUTES)) {
            return;
        }
        destroyTree(cloud);
        cloud.waitFor(30, TimeUnit.SECONDS);
    }

    private static int[] freePorts(int count) throws IOException {
        int[] ports = new int[count];
        Set<Integer> taken = new HashSet<>();
        for (int i = 0; i < count; ) {
            try (ServerSocket socket = new ServerSocket(0)) {
                int port = socket.getLocalPort();
                // Not next to another port: the OS hands out ports one after the other (on Windows
                // nearly always), and Geyser's NetherNet takes the port after the Bedrock port, which
                // the setup check refuses to share with the legacy port.
                if ((port < 30000 || port > 30999) && !taken.contains(port - 1) && !taken.contains(port)
                        && !taken.contains(port + 1)) {
                    taken.add(port);
                    ports[i++] = port;
                }
            }
        }
        return ports;
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String tail(Path output) {
        String text = read(output);
        return text.length() <= 6000 ? text : text.substring(text.length() - 6000);
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        assertNotNull(value, name + " is set by the e2eTest task");
        return value;
    }
}
