package net.vectora.cloud.database;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * How the managed mode runs MariaDB's programs. {@code cloud-node} implements this with its own
 * process supervision (logs, restarts, shutdown hooks); this module only says what to run.
 */
public interface DatabaseProcesses {

    /**
     * Runs a program to completion, for example {@code mariadb-install-db}.
     *
     * @param command what to run
     * @param timeout how long it may take; afterwards the process is killed
     * @return the exit code
     * @throws IOException if the program cannot be started or did not finish in time
     * @throws InterruptedException if interrupted while waiting
     */
    int run(Command command, Duration timeout) throws IOException, InterruptedException;

    /**
     * Starts a long-running program, the database server.
     *
     * @param command what to run
     * @return a handle to the running process
     * @throws IOException if the program cannot be started
     */
    Running start(Command command) throws IOException;

    /**
     * A program call.
     *
     * @param arguments program and arguments; the program is an absolute path
     * @param workingDirectory working directory
     * @param logFile file that receives standard output and standard error (appended)
     */
    record Command(List<String> arguments, Path workingDirectory, Path logFile) {

        /**
         * Checks the values.
         *
         * @param arguments program and arguments
         * @param workingDirectory working directory
         * @param logFile log file
         */
        public Command {
            arguments = List.copyOf(arguments);
            if (arguments.isEmpty()) {
                throw new IllegalArgumentException("command without program");
            }
            Objects.requireNonNull(workingDirectory, "workingDirectory");
            Objects.requireNonNull(logFile, "logFile");
        }
    }

    /** A running program. */
    interface Running {

        /**
         * Tells whether the process still runs.
         *
         * @return whether it is alive
         */
        boolean isAlive();

        /**
         * Waits for the process to end.
         *
         * @param timeout how long to wait
         * @return whether it ended within the time
         * @throws InterruptedException if interrupted
         */
        boolean waitFor(Duration timeout) throws InterruptedException;

        /** Kills the process. Only the last resort after a clean shutdown failed. */
        void kill();
    }
}
