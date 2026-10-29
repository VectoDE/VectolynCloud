package net.vectora.cloud.node.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.vectora.cloud.database.DatabaseProcesses;
import net.vectora.cloud.node.service.JavaRuntimeResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeDatabaseProcessesTest {

    /** Set by the Gradle test task, so the node's own environment holds a VECTORA_ variable. */
    private static final String MARKER = "VECTORA_TEST_ENV_MARKER";

    @TempDir
    Path dir;

    /** Stand-in for a MariaDB program: prints the environment, or sleeps with {@code sleep}. */
    public static final class Program {
        private Program() {
        }

        /**
         * Runs.
         *
         * @param args {@code sleep} to never end
         * @throws InterruptedException never in practice
         */
        public static void main(String[] args) throws InterruptedException {
            System.getenv().keySet().stream().sorted().forEach(key -> System.out.println("env " + key));
            System.out.println("done");
            if (args.length > 0 && args[0].equals("sleep")) {
                Thread.sleep(Long.MAX_VALUE);
            }
        }
    }

    private DatabaseProcesses.Command command(String... args) throws IOException {
        List<String> arguments = new ArrayList<>(List.of(JavaRuntimeResolver.JavaRuntime.current().executable().toString(),
                "-cp", System.getProperty("java.class.path"), Program.class.getName()));
        arguments.addAll(List.of(args));
        return new DatabaseProcesses.Command(arguments, dir, dir.resolve("logs").resolve("mariadb.log"));
    }

    @Test
    void runsWithoutTheCloudsVariablesAndAppendsToTheLog() throws Exception {
        assertEquals("leak", System.getenv(MARKER), "the test task sets " + MARKER);
        NodeDatabaseProcesses processes = new NodeDatabaseProcesses();
        assertEquals(0, processes.run(command(), Duration.ofSeconds(60)));
        assertEquals(0, processes.run(command(), Duration.ofSeconds(60)));
        String log = Files.readString(dir.resolve("logs").resolve("mariadb.log"), StandardCharsets.UTF_8);
        assertEquals(2, log.lines().filter("done"::equals).count(), "both runs appended: " + log);
        assertFalse(log.contains("env VECTORA_"), "no VECTORA_ variable reaches the program: " + log);
        assertTrue(log.contains("env "), "the rest of the environment is kept");
    }

    @Test
    void aRelativeProgramIsRefused() {
        IOException refused = assertThrows(IOException.class, () -> new NodeDatabaseProcesses().run(
                new DatabaseProcesses.Command(List.of("mariadbd"), dir, dir.resolve("x.log")), Duration.ofSeconds(5)));
        assertTrue(refused.getMessage().contains("absolute"), refused.getMessage());
    }

    @Test
    void aProgramThatTakesTooLongIsKilled() throws Exception {
        NodeDatabaseProcesses processes = new NodeDatabaseProcesses();
        IOException late = assertThrows(IOException.class, () -> processes.run(command("sleep"), Duration.ofSeconds(3)));
        assertTrue(late.getMessage().contains("did not finish"), late.getMessage());

        DatabaseProcesses.Running running = processes.start(command("sleep"));
        assertTrue(running.isAlive());
        assertFalse(running.waitFor(Duration.ofMillis(200)));
        running.kill();
        assertTrue(running.waitFor(Duration.ofSeconds(30)), "killed");
        assertFalse(running.isAlive());
    }
}
