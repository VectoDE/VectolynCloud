package net.vectora.cloud.download.jre;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Asks a Java executable for its settings by running {@code java -XshowSettings:properties -version}.
 * The command prints and exits at once; it does not run any application code.
 */
@FunctionalInterface
public interface JavaProbe {

    /**
     * Runs the executable and parses what it prints.
     *
     * @param executable the {@code java} or {@code java.exe} to ask
     * @return the settings, or empty if it does not run, takes too long or exits with an error
     * @throws InterruptedException if interrupted while waiting
     */
    Optional<JavaSettings> probe(Path executable) throws InterruptedException;

    /**
     * Returns the probe that starts a process, with a timeout of 15 seconds.
     *
     * @return the probe
     */
    static JavaProbe process() {
        return process(Duration.ofSeconds(15));
    }

    /**
     * Returns the probe that starts a process.
     *
     * @param timeout how long the JVM may take
     * @return the probe
     */
    static JavaProbe process(Duration timeout) {
        return executable -> {
            Process process;
            try {
                process = new ProcessBuilder(List.of(executable.toString(), "-XshowSettings:properties", "-version"))
                        .redirectErrorStream(true)
                        .start();
            } catch (IOException | RuntimeException e) {
                return Optional.empty();
            }
            // The read below blocks until the JVM ends; a JVM that hangs is killed after the timeout.
            CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS).execute(process::destroyForcibly);
            try {
                // Read while the JVM runs, so a full pipe cannot block it; the limit caps memory.
                byte[] output;
                try (InputStream in = process.getInputStream()) {
                    process.getOutputStream().close();
                    output = in.readNBytes(JavaSettings.MAX_CHARS);
                } catch (IOException e) {
                    return Optional.empty();
                }
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                    return Optional.empty();
                }
                return Optional.of(JavaSettings.parse(new String(output, StandardCharsets.UTF_8)));
            } finally {
                process.destroyForcibly();
            }
        };
    }
}
