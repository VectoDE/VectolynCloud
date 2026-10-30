package net.vectolyn.cloud.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.model.ApiConfig;
import net.vectolyn.cloud.config.operation.ConfigOperation;
import net.vectolyn.cloud.config.operation.SetApi;
import net.vectolyn.cloud.console.Console;
import net.vectolyn.cloud.console.command.CommandDispatcher.Outcome;
import net.vectolyn.cloud.console.terminal.PlainTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a broken part of the home or a taken port does to the whole node: it starts, runs without
 * the part, and says why.
 */
@Timeout(180)
class NodeRobustnessTest {

    @TempDir
    Path dir;

    /** Collects the log records of the node's loggers. */
    private static final class Log implements AutoCloseable {
        final List<LogRecord> records = new ArrayList<>();
        private final Logger logger = Logger.getLogger("net.vectolyn.cloud");
        private final Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                synchronized (records) {
                    records.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };

        Log() {
            logger.addHandler(handler);
        }

        /** The formatted messages at {@code level}. */
        List<String> at(Level level) {
            synchronized (records) {
                return records.stream().filter(r -> r.getLevel().equals(level))
                        .map(r -> r.getParameters() == null ? r.getMessage()
                                : java.text.MessageFormat.format(r.getMessage(), r.getParameters()))
                        .toList();
            }
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
        }
    }

    private static final class Screen {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final Console console = Console.builder().terminal(new PlainTerminal(new ByteArrayInputStream(new byte[0]),
                new PrintStream(bytes, true, StandardCharsets.UTF_8))).build();

        String take() {
            String text = bytes.toString(StandardCharsets.UTF_8);
            bytes.reset();
            return text;
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static ConfigOperation api(int port) {
        return new SetApi(ApiConfig.defaults().withEnabled(true).withPort(port));
    }

    private void brokenTokenFile(NodeFixture fixture) throws Exception {
        // Not a token file: no value of a real file is used here.
        fixture.file("secrets/api-tokens.json", "{ \"schema-version\": 1, \"tokens\": [ { \"id\": 7 ");
    }

    /** Live platform threads of an API server; the node's virtual request threads do not show. */
    private static Set<Thread> apiThreads() {
        Set<Thread> threads = new HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith("vectolyn-api")) {
                threads.add(thread);
            }
        }
        return threads;
    }

    private static Set<Thread> newApiThreads(Set<Thread> before) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Set<Thread> now = apiThreads();
        now.removeAll(before);
        while (!now.isEmpty() && System.nanoTime() < end) {
            Thread.sleep(20);
            now = apiThreads();
            now.removeAll(before);
        }
        return now;
    }

    @Test
    void withTheApiOffABrokenTokenFileIsOnlyAWarning() throws Exception {
        NodeFixture fixture = NodeFixture.create(dir, false, List.of());
        brokenTokenFile(fixture);
        Screen screen = new Screen();
        try (Log log = new Log()) {
            CloudNode node = CloudNode.open(fixture.settings(), fixture.options().andThen(o -> o.console(screen.console)));
            try {
                node.start();
                assertTrue(log.at(Level.WARNING).stream().anyMatch(m -> m.contains("api-tokens.json cannot be read")),
                        log.at(Level.WARNING).toString());
                assertFalse(log.at(Level.SEVERE).stream().anyMatch(m -> m.contains("api-tokens.json")),
                        "no error while the API is off: " + log.at(Level.SEVERE));
                screen.take();
                assertEquals(Outcome.FAILED, screen.console.dispatcher().execute("api token list"));
                assertTrue(screen.take().contains("secrets/api-tokens.json cannot be read"));
            } finally {
                node.close();
            }
        }
    }

    @Test
    void withTheApiOnABrokenTokenFileLeavesTheNodeRunningWithoutTheApi() throws Exception {
        int port = freePort();
        NodeFixture fixture = NodeFixture.create(dir, false, List.of(api(port)));
        brokenTokenFile(fixture);
        Set<Thread> before = apiThreads();
        Screen screen = new Screen();
        try (Log log = new Log()) {
            CloudNode node = CloudNode.open(fixture.settings(), fixture.options().andThen(o -> o.console(screen.console)));
            try {
                node.start();
                assertTrue(log.at(Level.SEVERE).stream().anyMatch(m -> m.contains("api-tokens.json cannot be read")
                        && m.contains("runs without it")), log.at(Level.SEVERE).toString());
                assertEquals(Set.of(), newApiThreads(before), "no API server was started");
                screen.take();
                assertEquals(Outcome.FAILED, screen.console.dispatcher().execute("api token create dash admin"));
                assertTrue(screen.take().contains("secrets/api-tokens.json cannot be read"));
                // Counter-check: the node itself works.
                assertTrue(node.services().services().isEmpty());
            } finally {
                node.close();
            }
        }
    }

    @Test
    void aTakenApiPortLeavesTheNodeRunningAndNoApiThreadBehind() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            NodeFixture fixture = NodeFixture.create(dir, false, List.of(api(taken.getLocalPort())));
            Set<Thread> before = apiThreads();
            Screen screen = new Screen();
            try (Log log = new Log()) {
                CloudNode node = CloudNode.open(fixture.settings(),
                        fixture.options().andThen(o -> o.console(screen.console)));
                try {
                    node.start();
                    assertTrue(log.at(Level.SEVERE).stream().anyMatch(m -> m.contains("The API cannot start")
                            && m.contains("port " + taken.getLocalPort())), log.at(Level.SEVERE).toString());
                    assertEquals(Set.of(), newApiThreads(before), "both event loop groups were shut down");
                    // The token commands still work: the file itself is fine.
                    screen.take();
                    assertEquals(Outcome.DONE, screen.console.dispatcher().execute("api token list"));
                } finally {
                    node.close();
                }
            }
        }
    }

    @Test
    void anInvalidOrUnknownNodeIdIsRefusedAtTheStart() throws Exception {
        NodeFixture fixture = NodeFixture.create(dir, false, List.of());
        Path cloud = fixture.home.resolve("config/cloud.yml");
        String valid = Files.readString(cloud, StandardCharsets.UTF_8);
        assertTrue(valid.matches("(?s).*\\nnode-id: [a-z0-9-]+\\n.*"), valid);

        for (String bad : List.of("\"Node 1!\"", "\"../node\"", "node-9")) {
            Files.writeString(cloud, valid.replaceFirst("\\nnode-id: [a-z0-9-]+\\n", "\nnode-id: " + bad + "\n"),
                    StandardCharsets.UTF_8);
            ConfigException refused = assertThrows(ConfigException.class,
                    () -> CloudNode.open(fixture.settings(), fixture.options()), bad);
            assertTrue(refused.errors().stream().anyMatch(e -> e.file().endsWith("cloud.yml") && e.path().equals("node-id")),
                    bad + ": " + refused.errors());
        }

        // Counter-check: with the id put back the node opens, so the refusal also let go of the home.
        Files.writeString(cloud, valid, StandardCharsets.UTF_8);
        CloudNode.open(fixture.settings(), fixture.options()).close();
    }
}