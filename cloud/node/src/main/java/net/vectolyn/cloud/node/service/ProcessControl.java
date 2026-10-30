package net.vectolyn.cloud.node.service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The operating system side of the node: starting processes and looking at processes it did not
 * start in this run. The default implementation uses {@link ProcessBuilder} and
 * {@link ProcessHandle} and works the same on Windows, Linux and macOS; tests replace it.
 */
public interface ProcessControl {

    /**
     * Starts a process. Standard error is merged into standard output.
     *
     * @param command     executable and arguments
     * @param directory   working directory
     * @param environment the complete environment of the new process
     * @return the running process
     * @throws IOException if the process cannot be started
     */
    Process start(List<String> command, Path directory, Map<String, String> environment) throws IOException;

    /**
     * Looks up a process by pid.
     *
     * @param pid the process id
     * @return its handle while it is alive, otherwise empty
     */
    Optional<ProcessHandle> find(long pid);

    /**
     * Ends a process that the node cannot talk to (no standard input): first the normal
     * termination request (SIGTERM on Linux and macOS, which Paper and Velocity answer with a clean
     * shutdown), then after {@code grace} the forced one.
     *
     * @param process the process
     * @param grace   how long the process may take to end after the first request
     * @return whether the process ended
     */
    boolean terminate(ProcessHandle process, Duration grace);

    /**
     * Start time of a process as the operating system reports it.
     *
     * @param process the process
     * @return the start time, if known
     */
    default Optional<Instant> startTime(ProcessHandle process) {
        return process.info().startInstant();
    }

    /**
     * The default implementation.
     *
     * @return process control through {@link ProcessBuilder} and {@link ProcessHandle}
     */
    static ProcessControl system() {
        return new ProcessControl() {
            @Override
            public Process start(List<String> command, Path directory, Map<String, String> environment)
                    throws IOException {
                ProcessBuilder builder = new ProcessBuilder(command)
                        .directory(directory.toFile())
                        .redirectErrorStream(true);
                Map<String, String> env = builder.environment();
                env.clear();
                env.putAll(environment);
                return builder.start();
            }

            @Override
            public Optional<ProcessHandle> find(long pid) {
                return ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
            }

            @Override
            public boolean terminate(ProcessHandle process, Duration grace) {
                process.destroy();
                if (waitFor(process, grace)) {
                    return true;
                }
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return waitFor(process, Duration.ofSeconds(10));
            }

            private boolean waitFor(ProcessHandle process, Duration timeout) {
                try {
                    process.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return !process.isAlive();
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                    return !process.isAlive();
                }
            }
        };
    }
}
