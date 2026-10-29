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
import net.vectora.cloud.config.operation.PutGroup;
import net.vectora.cloud.config.operation.SetLegacyProxy;
import net.vectora.cloud.config.operation.SetServerWanted;
import net.vectora.cloud.console.Console;
import net.vectora.cloud.console.terminal.PlainTerminal;
import net.vectora.cloud.node.service.JavaRuntimeResolver;
import net.vectora.cloud.node.service.ServiceException;
import net.vectora.cloud.node.service.ServiceState;
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

    @Test
    void theLegacyProxyRunsOnlyWhenEnabledAndGetsItsOwnForwardingSecret() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        NodeFixture fixture = NodeFixture.create(dir, true, List.of(
                new PutGroup("legacy-proxy", NodeFixture.group("Legacy-Proxy", ServerSoftware.VELOCITY,
                        "-Dfake.cloud=true")),
                NodeFixture.task("legacy-proxy", "legacy-proxy", TaskMode.STATIC, 1, 1)));
        fixture.file("Legacy-Proxy/template/velocity.toml", "bind = \"0.0.0.0:{{VECTORA_LEGACY_PROXY_PORT}}\"\n");
        ServiceId legacy = new ServiceId("legacy-proxy-1");

        CloudNode node = CloudNode.open(fixture.settings(), fixture.options());
        try {
            node.start();
            NodeFixture.await("lobby-1 running", () -> NodeEndToEndTest.state(node, new ServiceId("lobby-1"))
                    == ServiceState.RUNNING);
            assertTrue(node.services().find(legacy).isEmpty(), "disabled legacy proxy is not started");

            node.config().apply(new SetLegacyProxy(true, port));
            node.services().startConfigured();
            NodeFixture.await("legacy proxy running", () -> NodeEndToEndTest.state(node, legacy) == ServiceState.RUNNING);
            Path folder = node.services().find(legacy).orElseThrow().workDirectory();
            assertEquals(port, node.services().find(legacy).orElseThrow().port());
            String written = Files.readString(folder.resolve("forwarding.secret"), StandardCharsets.UTF_8);
            String legacySecret = node.secrets().load().get("VECTORA_LEGACY_FORWARDING_SECRET").orElseThrow().reveal();
            String mainSecret = node.secrets().load().get("VECTORA_FORWARDING_SECRET").orElseThrow().reveal();
            assertEquals(legacySecret, written.strip());
            assertFalse(written.contains(mainSecret), "never the main proxy's secret");
            assertTrue(Files.readString(folder.resolve("velocity.toml")).contains(":" + port + "\""));
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
            for (String name : List.of("service", "services", "shutdown", "help")) {
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
