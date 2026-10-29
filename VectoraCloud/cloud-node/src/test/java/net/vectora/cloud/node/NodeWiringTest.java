package net.vectora.cloud.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.model.ServerSoftware;
import net.vectora.cloud.config.model.TaskMode;
import net.vectora.cloud.config.operation.AcceptEula;
import net.vectora.cloud.config.operation.CompleteSetup;
import net.vectora.cloud.config.operation.PutGroup;
import net.vectora.cloud.config.operation.SetGroupWanted;
import net.vectora.cloud.config.operation.SetLegacyProxy;
import net.vectora.cloud.config.operation.SetServerWanted;
import net.vectora.cloud.console.Console;
import net.vectora.cloud.console.terminal.PlainTerminal;
import net.vectora.cloud.node.service.JavaRuntimeResolver;
import net.vectora.cloud.node.service.ServiceException;
import net.vectora.cloud.node.service.ServiceState;
import net.vectora.cloud.setup.SetupResult;
import net.vectora.protocol.ServiceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** How the node puts its parts together: home lock, start gate, reload, legacy proxy, console. */
class NodeWiringTest {

    @TempDir
    Path dir;

    /** Holds the home lock of {@code args[0]} in another process until its stdin ends. */
    public static final class HoldHomeLock {
        private HoldHomeLock() {
        }

        /**
         * Runs.
         *
         * @param args the home
         * @throws Exception on failure
         */
        public static void main(String[] args) throws Exception {
            try (HomeLock lock = HomeLock.acquire(Path.of(args[0]))) {
                System.out.println("held " + lock.isHeld());
                System.out.flush();
                System.in.read();
            }
        }
    }

    @Test
    void theHomeLockKeepsASecondCloudOutAndLeftoversAreRemoved() throws Exception {
        NodeFixture fixture = NodeFixture.create(dir, false, List.of());
        Path part = fixture.home.resolve("cache/artifacts/download-123.part");
        Path staging = fixture.home.resolve("jre/.staging-123/bin/java");
        Path kept = fixture.home.resolve("cache/artifacts/paper.jar");
        for (Path file : List.of(part, staging, kept)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "x");
        }

        CloudNode node = CloudNode.open(fixture.settings(), fixture.options());
        try {
            assertFalse(Files.exists(part), "unfinished download removed");
            assertFalse(Files.exists(fixture.home.resolve("jre/.staging-123")), "unfinished Java install removed");
            assertTrue(Files.exists(kept), "finished downloads stay");
            HomeLock.HomeLockedException second = assertThrows(HomeLock.HomeLockedException.class,
                    () -> CloudNode.open(fixture.settings(), fixture.options()));
            assertTrue(second.getMessage().contains("Another VectoraCloud"), second.getMessage());
        } finally {
            node.close();
        }

        // Another process holds it.
        Process holder = new ProcessBuilder(JavaRuntimeResolver.JavaRuntime.current().executable().toString(), "-cp",
                System.getProperty("java.class.path"), HoldHomeLock.class.getName(), fixture.home.toString())
                .redirectErrorStream(true).start();
        try (BufferedReader out = new BufferedReader(new InputStreamReader(holder.getInputStream(),
                StandardCharsets.UTF_8))) {
            assertEquals("held true", out.readLine());
            assertThrows(HomeLock.HomeLockedException.class, () -> CloudNode.open(fixture.settings(), fixture.options()));
            holder.getOutputStream().close();
            assertTrue(holder.waitFor(30, TimeUnit.SECONDS));
        } finally {
            holder.destroyForcibly();
        }
        CloudNode.open(fixture.settings(), fixture.options()).close();
    }

    @Test
    void withoutSetupAndEulaNoServiceStartsEvenWhenAskedFor() throws Exception {
        NodeFixture fixture = NodeFixture.create(dir, false, List.of());
        CloudNode node = CloudNode.open(fixture.settings(), fixture.options());
        try {
            node.start();
            ServiceException refused = assertThrows(ServiceException.class, () -> node.services().start("lobby"));
            assertTrue(refused.getMessage().contains("setup"), refused.getMessage());
            assertThrows(ServiceException.class, () -> node.services().restart(new ServiceId("lobby-1")));
            assertTrue(node.services().services().isEmpty());
            assertEquals(0, fixture.database.provisions.get(), "the database is not set up for nothing");
        } finally {
            node.close();
        }
        assertEquals(0, fixture.database.shutdowns.get());
    }

    @Test
    void aGroupSetToUnwantedStopsCleanlyAndIsNotStartedAgain() throws Exception {
        NodeFixture fixture = NodeFixture.create(dir, true, List.of());
        ServiceId lobby = new ServiceId("lobby-1");
        CloudNode node = CloudNode.open(fixture.settings(), fixture.options());
        try {
            node.start();
            NodeFixture.await("lobby-1 running", () -> NodeEndToEndTest.state(node, lobby) == ServiceState.RUNNING);
            Path folder = node.services().find(lobby).orElseThrow().workDirectory();

            node.config().apply(new SetServerWanted(ServerKind.LOBBY, false));
            NodeFixture.await("lobby-1 stopped", () -> NodeEndToEndTest.state(node, lobby) == ServiceState.STOPPED);
            assertTrue(Files.exists(folder.resolve("stopped-cleanly")), "stopped with its stop command");
            assertThrows(ServiceException.class, () -> node.services().start("lobby"));
            node.services().startConfigured();
            Thread.sleep(500);
            assertEquals(ServiceState.STOPPED, NodeEndToEndTest.state(node, lobby), "not planned any more");
        } finally {
            node.close();
        }
    }

    /** Answers for the lobby only; every other server is off, so no group without a template is created. */
    private static final String SETUP_FILE = """
            schema-version: 1
            eula: %s
            language: en
            servers:
              proxy: false
              lobby: true
              survival: false
              creative: false
              mod: false
              end: false
              minigame: false
              legacy-proxy: false
            memory:
              lobby: 256M
            database:
              mode: h2
            """;

    @Test
    void aSetupFileFinishesTheSetupBeforeTheFirstServerStarts() throws Exception {
        NodeFixture fixture = NodeFixture.create(dir, false, List.of());
        Path answers = dir.resolve("setup.yml");
        Files.writeString(answers, SETUP_FILE.formatted("true"));
        CloudNode node = CloudNode.open(fixture.settings(java.util.Map.of("non-interactive", "true",
                "setup-file", answers.toString())), fixture.options());
        try {
            node.start();
            assertEquals(java.util.Optional.of(SetupResult.Status.COMPLETED), node.setupStatus());
            assertTrue(node.config().snapshot().cloud().setupComplete());
            NodeFixture.await("lobby-1 running", () -> NodeEndToEndTest.state(node, new ServiceId("lobby-1"))
                    == ServiceState.RUNNING);
        } finally {
            node.close();
        }
        // A second start leaves the file alone: the setup is complete.
        Files.writeString(answers, SETUP_FILE.formatted("false"));
        CloudNode again = CloudNode.open(fixture.settings(java.util.Map.of("non-interactive", "true",
                "setup-file", answers.toString())), fixture.options());
        try {
            again.start();
            assertEquals(java.util.Optional.of(SetupResult.Status.ALREADY_COMPLETE), again.setupStatus());
        } finally {
            again.close();
        }
    }

    @Test
    void aDeclinedEulaOrMissingAnswersStartNothingUntilTheSetupIsDone() throws Exception {
        NodeFixture fixture = NodeFixture.create(dir, false, List.of());
        Path answers = dir.resolve("setup.yml");
        Files.writeString(answers, SETUP_FILE.formatted("false"));
        for (java.util.Map<String, String> settings : List.of(
                java.util.Map.of("non-interactive", "true", "setup-file", answers.toString()),
                java.util.Map.of("non-interactive", "true"),
                java.util.Map.<String, String>of())) {
            CloudNode node = CloudNode.open(fixture.settings(settings), fixture.options());
            try {
                node.start();
                assertEquals(java.util.Optional.empty(), node.setupStatus(), settings.toString());
                assertFalse(node.config().snapshot().cloud().setupComplete());
                assertTrue(node.services().services().isEmpty(), settings.toString());
                assertEquals(0, fixture.database.provisions.get(), "the database is not set up either");
            } finally {
                node.close();
            }
        }

        // Once the setup is finished while the node runs (the "setup" command, or a hand edit),
        // the configured servers start without a restart.
        CloudNode node = CloudNode.open(fixture.settings(), fixture.options());
        try {
            node.start();
            assertTrue(node.services().services().isEmpty());
            node.config().apply(List.of(new AcceptEula(), new CompleteSetup()));
            NodeFixture.await("lobby-1 running", () -> NodeEndToEndTest.state(node, new ServiceId("lobby-1"))
                    == ServiceState.RUNNING);
        } finally {
            node.close();
        }
    }

    @Test
    void aFailingShutdownStepDoesNotSkipTheOthers() throws Exception {
        NodeFixture fixture = NodeFixture.create(dir, true, List.of());
        fixture.database.failShutdown = true;
        CloudNode node = CloudNode.open(fixture.settings(), fixture.options());
        node.start();
        NodeFixture.await("lobby-1 running", () -> NodeEndToEndTest.state(node, new ServiceId("lobby-1"))
                == ServiceState.RUNNING);
        java.net.InetSocketAddress channel = node.network().localAddress();
        LinkageError error = assertThrows(LinkageError.class, node::close, "thrown once every step has run");
        assertTrue(error.getMessage().contains("does not stop"), error.getMessage());
        assertEquals(1, fixture.database.shutdowns.get(), "the failing step ran");
        assertEquals(ServiceState.STOPPED, NodeEndToEndTest.state(node, new ServiceId("lobby-1")));
        assertThrows(java.io.IOException.class, () -> {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(channel, 1000);
            }
        }, "the channel after the failing database step is closed too");
        try (HomeLock again = HomeLock.acquire(fixture.home)) {
            assertTrue(again.isHeld(), "and the home lock is released");
        }
    }

    @Test
    void theGroupsOwnSwitchStopsItOnReloadAndSwitchingItOnAllowsTheStartAgain() throws Exception {
        NodeFixture fixture = NodeFixture.create(dir, true, List.of());
        ServiceId lobby = new ServiceId("lobby-1");
        CloudNode node = CloudNode.open(fixture.settings(), fixture.options());
        try {
            node.start();
            NodeFixture.await("lobby-1 running", () -> NodeEndToEndTest.state(node, lobby) == ServiceState.RUNNING);

            node.config().apply(new SetGroupWanted("lobby", false));
            NodeFixture.await("lobby-1 stopped", () -> NodeEndToEndTest.state(node, lobby) == ServiceState.STOPPED);
            ServiceException refused = assertThrows(ServiceException.class, () -> node.services().start("lobby"));
            assertTrue(refused.getMessage().contains("wanted: false"), refused.getMessage());

            // Counter-check: switched on again, "service start" brings it back (no automatic start).
            node.config().apply(new SetGroupWanted("lobby", true));
            Thread.sleep(300);
            assertEquals(ServiceState.STOPPED, NodeEndToEndTest.state(node, lobby), "not started by the reload");
            node.services().start("lobby");
            NodeFixture.await("lobby-1 running again", () -> NodeEndToEndTest.state(node, lobby) == ServiceState.RUNNING);
        } finally {
            node.close();
        }
    }

    @Test
    void theLegacyProxyRunsOnlyWhenEnabledAndGetsItsOwnForwardingSecret() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        NodeFixture fixture = NodeFixture.create(dir, true, List.of(
                new PutGroup("legacy-proxy", NodeFixture.group("Legacy-Proxy", ServerSoftware.VELOCITY,
                        "-Dfake.cloud=true")),
                NodeFixture.task("legacy-proxy", "legacy-proxy", TaskMode.STATIC, 1, 1),
                new PutGroup("legacy", NodeFixture.group("Legacy-Server", ServerSoftware.PANDASPIGOT, "-Dfake.cloud=true")
                        .withMinecraftVersion("1.8.8")),
                NodeFixture.task("legacy", "legacy", TaskMode.STATIC, 1, 1)));
        fixture.file("Legacy-Proxy/template/velocity.toml", "bind = \"0.0.0.0:{{VECTORA_LEGACY_PROXY_PORT}}\"\n"
                + "[servers]\n{{VECTORA_PROXY_SERVERS}}\ntry = [ {{VECTORA_PROXY_TRY}} ]\n");
        fixture.file("Legacy-Server/template/server.properties", "server-port={{VECTORA_SERVER_PORT}}\n");
        ServiceId legacy = new ServiceId("legacy-proxy-1");
        ServiceId panda = new ServiceId("legacy-1");

        CloudNode node = CloudNode.open(fixture.settings(), fixture.options());
        try {
            node.start();
            NodeFixture.await("lobby-1 running", () -> NodeEndToEndTest.state(node, new ServiceId("lobby-1"))
                    == ServiceState.RUNNING);
            assertTrue(node.services().find(legacy).isEmpty(), "disabled legacy proxy is not started");
            assertTrue(node.services().find(panda).isEmpty(), "nor the 1.8.8 server behind it");

            node.config().apply(new SetLegacyProxy(true, port));
            node.services().startConfigured();
            NodeFixture.await("legacy proxy running", () -> NodeEndToEndTest.state(node, legacy) == ServiceState.RUNNING);
            NodeFixture.await("1.8.8 server running", () -> NodeEndToEndTest.state(node, panda) == ServiceState.RUNNING);
            Path proxyLog = fixture.home.resolve("logs/services/legacy-proxy-1.log");
            Path pandaLog = fixture.home.resolve("logs/services/legacy-1.log");
            int pandaPort = node.services().find(panda).orElseThrow().port();
            NodeFixture.await("1.8.8 server registered at the legacy proxy", () -> NodeFixture.read(proxyLog)
                    .contains("[fake] register legacy 127.0.0.1:" + pandaPort + " group=legacy legacy=true"));
            assertFalse(NodeFixture.read(proxyLog).contains("register lobby"), "the legacy proxy gets no 26.x backend");
            assertTrue(NodeFixture.read(proxyLog).contains("[fake] platform VELOCITY_LEGACY"));
            assertTrue(NodeFixture.read(proxyLog).contains("[fake] legacy secret absent"),
                    "the legacy proxy reads its secret from forwarding.secret, not from the environment");
            assertTrue(NodeFixture.read(pandaLog).contains("[fake] platform PANDASPIGOT"));
            assertTrue(NodeFixture.read(pandaLog).contains("[fake] legacy secret present"), NodeFixture.read(pandaLog));
            assertTrue(NodeFixture.read(fixture.home.resolve("logs/services/lobby-1.log"))
                    .contains("[fake] legacy secret absent"), "counter-check: a modern backend gets none");
            Path folder = node.services().find(legacy).orElseThrow().workDirectory();
            assertEquals(port, node.services().find(legacy).orElseThrow().port());
            String written = Files.readString(folder.resolve("forwarding.secret"), StandardCharsets.UTF_8);
            String legacySecret = node.secrets().load().get("VECTORA_LEGACY_FORWARDING_SECRET").orElseThrow().reveal();
            String mainSecret = node.secrets().load().get("VECTORA_FORWARDING_SECRET").orElseThrow().reveal();
            assertEquals(legacySecret, written.strip());
            assertFalse(written.contains(mainSecret), "never the main proxy's secret");
            assertTrue(Files.readString(folder.resolve("velocity.toml")).contains(":" + port + "\""));

            // servers.legacy-server off: the 1.8.8 server stops on the reload and leaves the list.
            node.config().apply(new SetServerWanted(ServerKind.LEGACY_SERVER, false));
            NodeFixture.await("1.8.8 server stopped", () -> NodeEndToEndTest.state(node, panda) == ServiceState.STOPPED);
            NodeFixture.await("unregistered", () -> NodeFixture.read(proxyLog).contains("[fake] unregister legacy"));
            assertEquals(ServiceState.RUNNING, NodeEndToEndTest.state(node, legacy), "the legacy proxy itself stays");
            ServiceException refused = assertThrows(ServiceException.class, () -> node.services().start("legacy"));
            assertTrue(refused.getMessage().contains("servers.legacy-server"), refused.getMessage());
        } finally {
            node.close();
        }
    }

    @Test
    void theServiceCommandsAreInTheConsole() throws Exception {
        NodeFixture fixture = NodeFixture.create(dir, false, List.of());
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        Console console = Console.builder().terminal(new PlainTerminal(new ByteArrayInputStream(new byte[0]),
                new PrintStream(screen, true, StandardCharsets.UTF_8))).build();
        CloudNode node = CloudNode.open(fixture.settings(), fixture.options().andThen(o -> o.console(console)));
        try {
            node.start();
            // The questions ran on the console and met the end of its input: cancelled, nothing starts.
            assertEquals(java.util.Optional.of(SetupResult.Status.CANCELLED), node.setupStatus());
            for (String name : List.of("service", "services", "shutdown", "help", "setup")) {
                assertTrue(console.commands().find(name).isPresent(), name);
            }
            console.dispatcher().execute("service list");
            console.dispatcher().execute("service info lobby-7");
            console.dispatcher().execute("service start lobby");
            String text = screen.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains("No services"), text);
            assertTrue(text.contains("Unknown service"), text);
            assertTrue(text.contains("setup"), "the gate's reason is shown: " + text);
        } finally {
            node.close();
        }
    }
}
