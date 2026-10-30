package net.vectolyn.cloud.backup.database;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Runs a short-lived tool such as {@code mariadb-dump} to its end. {@link ProcessToolRunner} is the
 * real one; tests replace it.
 */
@FunctionalInterface
public interface ToolRunner {

    /**
     * How the tool ended.
     *
     * @param exitCode its exit code
     * @param output the end of its standard output and error, at most
     *     {@link ProcessToolRunner#MAX_OUTPUT} characters
     */
    record Result(int exitCode, String output) {

        /** Checks the values. */
        public Result {
            Objects.requireNonNull(output, "output");
        }
    }

    /**
     * Runs {@code command} (program first, as an absolute path) without a shell.
     *
     * @param command program and arguments
     * @param workingDirectory working directory
     * @param timeout longest run; the process is killed after it
     * @return exit code and output
     * @throws IOException if it cannot start or runs too long
     * @throws InterruptedException if interrupted; the process is killed
     */
    Result run(List<String> command, Path workingDirectory, Duration timeout) throws IOException, InterruptedException;
}
