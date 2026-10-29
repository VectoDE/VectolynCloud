package net.vectora.cloud.backup.database;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs tools with {@link ProcessBuilder}: no shell, arguments as single strings, standard input
 * closed, output and errors merged and kept to the last {@value #MAX_OUTPUT} characters.
 *
 * <p>The child gets the node's environment without {@code VECTORA_*} variables (they may hold
 * secrets) and without {@code MYSQL_PWD} and {@code MYSQL_HOST}, which the MySQL and MariaDB
 * tools would otherwise read in place of the option file.
 */
public final class ProcessToolRunner implements ToolRunner {

    /** Characters of output kept for error messages. */
    public static final int MAX_OUTPUT = 8192;

    @Override
    public Result run(List<String> command, Path workingDirectory, Duration timeout)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        clean(builder.environment());
        Process process = builder.start();
        process.getOutputStream().close();
        StringBuilder output = new StringBuilder();
        Thread reader = Thread.ofPlatform().daemon().name("vectora-backup-tool").start(() -> drain(process, output));
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                throw new IOException(Path.of(command.getFirst()).getFileName() + " did not finish within "
                        + timeout.toSeconds() + " s");
            }
            reader.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            kill(process);
            throw e;
        }
        synchronized (output) {
            return new Result(process.exitValue(), output.toString());
        }
    }

    /** Removes variables a child tool must not see. */
    static void clean(Map<String, String> environment) {
        environment.keySet().removeIf(key -> {
            String upper = key.toUpperCase(Locale.ROOT);
            return upper.startsWith("VECTORA_") || upper.equals("MYSQL_PWD") || upper.equals("MYSQL_HOST");
        });
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void drain(Process process, StringBuilder output) {
        byte[] buffer = new byte[8192];
        try (InputStream in = process.getInputStream()) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                String chunk = new String(buffer, 0, n, StandardCharsets.UTF_8);
                synchronized (output) {
                    output.append(chunk);
                    if (output.length() > MAX_OUTPUT) {
                        output.delete(0, output.length() - MAX_OUTPUT);
                    }
                }
            }
        } catch (IOException e) {
            // The process ended or was killed; what was read is enough for the message.
        }
    }
}
