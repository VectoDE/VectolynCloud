package net.vectora.cloud.node;

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
import net.vectora.cloud.console.Console;
import net.vectora.cloud.console.terminal.PlainTerminal;
import net.vectora.cloud.node.service.ServiceInfo;
import net.vectora.cloud.node.service.ServiceState;
import net.vectora.protocol.AuthToken;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.AuthResult;
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
            assertEquals(AuthResult.Outcome.ACCEPTED, auth(node, lobby, token));
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

    static ServiceState state(CloudNode node, ServiceId id) {
        return node.services().find(id).map(ServiceInfo::state).orElse(null);
    }

    static AuthResult.Outcome auth(CloudNode node, ServiceId id, String token) {
        return node.services().authenticator().authenticate(id, new AuthToken(token));
    }
}
