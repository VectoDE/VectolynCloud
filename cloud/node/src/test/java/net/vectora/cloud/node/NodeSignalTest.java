package net.vectora.cloud.node;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.vectora.cloud.node.service.JavaRuntimeResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** SIGTERM (docker stop, systemd) ends a node with a plain console cleanly. */
class NodeSignalTest {

    @TempDir
    Path dir;

    /** Runs a test node through {@link NodeMain}'s start and shutdown hook: home and fake jar as arguments. */
    public static final class TestNodeMain {
        private TestNodeMain() {
        }

        /**
         * Runs.
         *
         * @param args home and fake jar
         * @throws Exception on failure
         */
        public static void main(String[] args) throws Exception {
            NodeFixture fixture = NodeFixture.existing(Path.of(args[0]), Path.of(args[1]));
            NodeMain.start(NodeSettings.parse(Map.of("home", args[0])), fixture.withConsole());
        }
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Process.destroy() is TerminateProcess on Windows, no signal")
    void sigtermStopsTheServicesCleanlyAndReleasesTheHome() throws Exception {
        NodeFixture fixture = NodeFixture.create(dir, true, List.of());
        Process node = new ProcessBuilder(JavaRuntimeResolver.JavaRuntime.current().executable().toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
                TestNodeMain.class.getName(), fixture.home.toString(), fixture.fakeJar.toString())
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("node.log").toFile())
                .start();
        try {
            Path log = fixture.home.resolve("logs/services/lobby-1.log");
            NodeFixture.await("lobby-1 connected", () -> NodeFixture.read(log).contains("[fake] cloud connected"));
            node.destroy(); // SIGTERM
            assertTrue(node.waitFor(60, TimeUnit.SECONDS), "the node ends on SIGTERM");
        } finally {
            node.destroyForcibly();
        }
        String output = NodeFixture.read(dir.resolve("node.log"));
        assertTrue(Files.exists(fixture.home.resolve("local/services/lobby-1/stopped-cleanly")),
                "the server got its stop command: " + output);
        try (HomeLock again = HomeLock.acquire(fixture.home)) {
            assertTrue(again.isHeld(), "the home lock is free again");
        }
    }
}
