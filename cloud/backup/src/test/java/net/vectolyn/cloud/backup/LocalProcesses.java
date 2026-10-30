package net.vectolyn.cloud.backup;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import net.vectolyn.cloud.database.DatabaseProcesses;

/**
 * Plain {@link ProcessBuilder} implementation for the integration test (a copy of the one in
 * cloud-database's tests). In the cloud, {@code cloud-node} provides its own, supervised implementation.
 */
final class LocalProcesses implements DatabaseProcesses {

    @Override
    public int run(Command command, Duration timeout) throws IOException, InterruptedException {
        Process process = builder(command).start();
        process.getOutputStream().close();
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("did not finish within " + timeout.toSeconds() + " s: " + command.arguments().get(0));
        }
        return process.exitValue();
    }

    @Override
    public Running start(Command command) throws IOException {
        Process process = builder(command).start();
        process.getOutputStream().close();
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
                process.destroyForcibly();
            }
        };
    }

    private static ProcessBuilder builder(Command command) {
        return new ProcessBuilder(command.arguments())
                .directory(command.workingDirectory().toFile())
                .redirectErrorStream(true)
                .redirectOutput(Redirect.appendTo(command.logFile().toFile()));
    }
}
