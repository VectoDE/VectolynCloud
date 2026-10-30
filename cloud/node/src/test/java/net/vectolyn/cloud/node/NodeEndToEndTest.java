package net.vectolyn.cloud.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.vectolyn.cloud.config.model.ServerKind;
import net.vectolyn.cloud.config.model.ServerSoftware;
import net.vectolyn.cloud.config.model.TaskMode;
import net.vectolyn.cloud.config.operation.PutGroup;
import net.vectolyn.cloud.console.Console;
import net.vectolyn.cloud.console.terminal.PlainTerminal;
import net.vectolyn.cloud.node.service.ServiceInfo;
import net.vectolyn.cloud.node.service.ServiceState;
import net.vectolyn.protocol.AuthToken;
import net.vectolyn.protocol.ServiceId;
import net.vectolyn.protocol.message.AuthResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole node with real processes: the fake server starts, logs in to the cloud channel with
 * its token through {@code CloudClient}, reports ready, and the console's {@code service stop}
 * stops it cleanly and revokes the token.
 */
class NodeEndToEndTest {

    @TempDir
    Path dir;

    @Test
    void aServerLogsInReportsReadyAndTheConsoleStopsIt() throws Exception {
        NodeFixture fixture = NodeFixture.create(dir, true, List.of());
        PipedOutputStream keyboard = new PipedOutputStream();
        PipedInputStream input = new PipedInputStream(keyboard, 8192);
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        PrintStream screenOut = new PrintStream(screen, true, StandardCharsets.UTF_8);
        Console console = Console.builder().terminal(new PlainTerminal(input, screenOut)).build();
        ServiceId lobby = new ServiceId("lobby-1");

        CloudNode node = CloudNode.open(fixture.settings(), fixture.options().andThen(o -> o.console(console)));
        try {
            node.start();
            assertTrue(node.console().isPresent());

            // RUNNING only comes from ServiceReady on the channel: the fake prints no "Done (".
            NodeFixture.await("lobby-1 running", () -> state(node, lobby) == ServiceState.RUNNING);
            assertTrue(node.channel().isConnected(lobby), "the server is connected to the channel");
            assertEquals(1, fixture.database.provisions.get(), "the database is set up before the first service");
            ServiceInfo info = node.services().find(lobby).orElseThrow();
            Path folder = info.workDirectory();
            String token = Files.readString(folder.resolve("token.txt"), StandardCharsets.UTF_8);
            // The fake server has connected with it, so the token from its environment is used up:
            // a copy of it gets nobody in (issue #73).
            assertEquals(AuthResult.Outcome.INVALID_TOKEN, auth(node, lobby, token), "used up by the connection");
            assertTrue(NodeFixture.read(folder.resolve("server.properties")).contains("db-pool=4"),
                    "pool size placeholder from the connection budget");
            Path log = fixture.home.resolve("logs/services/lobby-1.log");
            NodeFixture.await("connected line", () -> NodeFixture.read(log).contains("[fake] cloud connected"));
            assertFalse(NodeFixture.read(log).contains(token), "the token is masked in the service log");

            // The console stops the service.
            keyboard.write("service stop lobby-1\n".getBytes(StandardCharsets.UTF_8));
            keyboard.flush();
            NodeFixture.await("lobby-1 stopped", () -> state(node, lobby) == ServiceState.STOPPED);
            assertTrue(Files.exists(folder.resolve("stopped-cleanly")), "stopped with its stop command");
            assertEquals(AuthResult.Outcome.UNKNOWN_PEER, auth(node, lobby, token), "the token died with the process");
            NodeFixture.await("channel closed", () -> !node.channel().isConnected(lobby));
            NodeFixture.await("console answer", () -> screen.toString(StandardCharsets.UTF_8).contains("lobby-1 is stopped"));
        } finally {
            node.close();
            keyboard.close();
        }
        assertEquals(1, fixture.database.shutdowns.get(), "the database is stopped after the services");
        assertFalse(console.isRunning());
        try (HomeLock again = HomeLock.acquire(fixture.home)) {
            assertTrue(again.isHeld(), "the home lock is released on close");
        }
    }

    /**
     * A proxy, a static and a dynamic backend, and a group switched off with {@code wanted: false}:
     * the database is set up before the first process and stopped after the last one, the proxy
     * gets the backends through {@code RegisterServer} (never the unwanted one), its player events
     * are mapped from the proxy's names to the services, and a stopped backend is unregistered.
     */
    @Test
    void theDatabaseFramesTheServicesAndTheProxyGetsOnlyWantedBackends() throws Exception {
        NodeFixture fixture = NodeFixture.create(dir, true, List.of(
                new PutGroup("proxy", NodeFixture.group(ServerKind.PROXY.templateFolder(), ServerSoftware.VELOCITY,
                        "-Dfake.cloud=true")),
                NodeFixture.task("proxy", "proxy", TaskMode.STATIC, 1, 1),
                new PutGroup("minigame", NodeFixture.group(ServerKind.MINIGAME.templateFolder(), ServerSoftware.PAPER,
                        "-Dfake.cloud=true")),
                NodeFixture.task("minigame", "minigame", TaskMode.DYNAMIC, 1, 2),
                new PutGroup("survival", NodeFixture.group(ServerKind.SURVIVAL.templateFolder(), ServerSoftware.PAPER,
                        "-Dfake.cloud=true").withWanted(false)),
                NodeFixture.task("survival", "survival", TaskMode.STATIC, 1, 1)));
        fixture.file("local/templates/Proxy/default/velocity.toml", """
                bind = "{{VECTOLYN_PROXY_BIND}}:{{VECTOLYN_PROXY_PORT}}"
                [servers]
                {{VECTOLYN_PROXY_SERVERS}}
                try = [ {{VECTOLYN_PROXY_TRY}} ]
                """);
        ServiceId lobby = new ServiceId("lobby-1");
        ServiceId minigame = new ServiceId("minigame-1");
        ServiceId proxy = new ServiceId("proxy-1");
        CloudNode node = CloudNode.open(fixture.settings(), fixture.options());
        fixture.database.services = node.services()::services;
        Path proxyLog = fixture.home.resolve("logs/services/proxy-1.log");
        try {
            node.start();
            for (ServiceId id : List.of(lobby, minigame, proxy)) {
                NodeFixture.await(id + " running", () -> state(node, id) == ServiceState.RUNNING);
            }
            assertEquals(List.of(), fixture.database.runningAtProvision, "no process before the database");
            assertTrue(node.services().find(new ServiceId("survival-1")).isEmpty(), "wanted: false is never created");
            assertFalse(Files.exists(fixture.home.resolve("local/services/survival-1")));
            assertFalse(Files.exists(fixture.home.resolve("Survival-Server")), "no old server folder at the top");
            String velocity = NodeFixture.read(fixture.home.resolve("local/services/proxy-1/velocity.toml"));
            assertTrue(velocity.contains("lobby = "), velocity);
            assertFalse(velocity.contains("survival"), velocity);

            int minigamePort = node.services().find(minigame).orElseThrow().port();
            int lobbyPort = node.services().find(lobby).orElseThrow().port();
            NodeFixture.await("minigame registered", () -> NodeFixture.read(proxyLog).contains(
                    "[fake] register minigame-1 127.0.0.1:" + minigamePort + " group=minigame legacy=false"));
            NodeFixture.await("lobby registered under its proxy name", () -> NodeFixture.read(proxyLog).contains(
                    "[fake] register lobby 127.0.0.1:" + lobbyPort + " group=lobby legacy=false"));
            assertFalse(NodeFixture.read(proxyLog).contains("register survival"));
            assertTrue(NodeFixture.read(proxyLog).contains("[fake] platform VELOCITY"));
            assertTrue(NodeFixture.read(proxyLog).contains("[fake] legacy secret absent"));
            Path lobbyLog = fixture.home.resolve("logs/services/lobby-1.log");
            assertTrue(NodeFixture.read(lobbyLog).contains("[fake] platform PAPER"), NodeFixture.read(lobbyLog));
            assertTrue(NodeFixture.read(lobbyLog).contains("[fake] legacy secret absent"));

            // Player events use the proxy's names; the node maps "lobby" to lobby-1.
            java.util.UUID steve = java.util.UUID.fromString("00000000-0000-4000-8000-000000000001");
            node.services().sendCommand(proxy, "player join " + steve + " Steve lobby");
            NodeFixture.await("join", () -> node.channel().players().find(steve)
                    .map(p -> p.service().equals(lobby)).orElse(false));
            node.services().sendCommand(proxy, "player switch " + steve + " Steve lobby minigame-1");
            NodeFixture.await("switch", () -> node.channel().players().find(steve)
                    .map(p -> p.service().equals(minigame)).orElse(false));
            assertEquals(List.of(steve), node.channel().players().on(minigame).stream()
                    .map(net.vectolyn.cloud.node.channel.NetworkPlayers.Player::uuid).toList());
            // A backend sends no player events; the node drops them.
            node.services().sendCommand(minigame, "player quit " + steve + " Steve minigame-1");
            node.services().sendCommand(proxy, "player quit " + steve + " Steve minigame-1");
            NodeFixture.await("quit", () -> node.channel().players().find(steve).isEmpty());

            node.services().stop(minigame);
            NodeFixture.await("minigame unregistered", () -> NodeFixture.read(proxyLog)
                    .contains("[fake] unregister minigame-1"));
            assertTrue(state(node, lobby) == ServiceState.RUNNING && state(node, proxy) == ServiceState.RUNNING,
                    "still running when the node closes");
        } finally {
            node.close();
        }
        assertEquals(1, fixture.database.provisions.get());
        assertEquals(1, fixture.database.shutdowns.get());
        assertEquals(List.of(), fixture.database.runningAtShutdown, "every process ended before the database");
        assertTrue(fixture.database.knownAtShutdown.containsAll(List.of("lobby-1", "proxy-1")),
                "the database stopped after the services, not before they were known: "
                        + fixture.database.knownAtShutdown);
    }

    static ServiceState state(CloudNode node, ServiceId id) {
        return node.services().find(id).map(ServiceInfo::state).orElse(null);
    }

    static AuthResult.Outcome auth(CloudNode node, ServiceId id, String token) {
        return node.services().authenticator().authenticate(id, new AuthToken(token));
    }
}
