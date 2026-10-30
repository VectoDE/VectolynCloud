package net.vectolyn.cloud.node.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.vectolyn.cloud.database.DatabaseProcesses;
import net.vectolyn.protocol.ServiceEnvironment;

/**
 * Runs MariaDB's programs for the managed database mode: {@code mariadb-install-db} to completion
 * and {@code mariadbd} as a long-running process.
 *
 * <ul>
 *   <li>The program must be an absolute path; no shell is involved.</li>
 *   <li>Standard output and standard error go together into the command's log file (appended).</li>
 *   <li>The environment is the node's own without any {@code VECTOLYN_*} variable, like for the
 *       servers, so no secret of the cloud's environment reaches MariaDB.</li>
 *   <li>A program that does not finish in time is killed, with its child processes.</li>
 * </ul>
 * Stopping the server cleanly is the provisioner's job ({@code SHUTDOWN} as root); the node calls
 * it after the last service has stopped.
 */
public final class NodeDatabaseProcesses implements DatabaseProcesses {

    private static final System.Logger LOG = System.getLogger(NodeDatabaseProcesses.class.getName());
    /** How long {@link #run} waits for a killed program to be gone. */
    static final Duration KILL_WAIT = Duration.ofSeconds(30);

    @Override
    public int run(Command command, Duration timeout) throws IOException, InterruptedException {
        Process process = launch(command);
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            kill(process);
            // Only report once it is gone: until then it still holds its log file and working folder
            // (on Windows nothing in there can be moved or deleted), and a retry would start a second copy.
            if (!process.waitFor(KILL_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.log(System.Logger.Level.WARNING, "{0} (pid {1}) is still running after it was killed",
                        programName(command), process.pid());
            }
            throw new IOException(programName(command) + " did not finish within " + timeout.toSeconds() + " s");
        }
        return process.exitValue();
    }

    @Override
    public Running start(Command command) throws IOException {
        Process process = launch(command);
        LOG.log(System.Logger.Level.INFO, "Started {0} (pid {1})", programName(command), process.pid());
        return new Running() {
            @Override
            public boolean isAlive() {
                return process.isAlive();
            }

            @Override
            public boolean waitFor(Duration timeout) throws InterruptedException {
                return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }

            @Override
            public void kill() {
                NodeDatabaseProcesses.kill(process);
            }
        };
    }

    private static Process launch(Command command) throws IOException {
        Path program = Path.of(command.arguments().getFirst());
        if (!program.isAbsolute()) {
            throw new IOException("program must be an absolute path: " + programName(command));
        }
        Path log = command.logFile().toAbsolutePath();
        Files.createDirectories(log.getParent());
        ProcessBuilder builder = new ProcessBuilder(command.arguments())
                .directory(command.workingDirectory().toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .redirectInput(ProcessBuilder.Redirect.PIPE);
        Map<String, String> environment = builder.environment();
        environment.keySet().removeIf(key -> key.toUpperCase(Locale.ROOT).startsWith(ServiceEnvironment.PREFIX));
        Process process = builder.start();
        process.getOutputStream().close();
        return process;
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static String programName(Command command) {
        Path name = Path.of(command.arguments().getFirst()).getFileName();
        return name == null ? command.arguments().getFirst() : name.toString();
    }
}
