package net.vectolyn.cloud.cluster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.vectolyn.cloud.config.secret.Secret;

/**
 * Runs the {@code keytool} of the running Java. Passwords reach it through environment variables
 * ({@code -storepass:env}), never on its command line, where other users of the machine could read
 * them.
 */
final class Keytool {

    /** Environment variable for the first password of a call. */
    static final String PASSWORD_ENV = "VECTOLYN_KEYTOOL_STOREPASS";
    /** Environment variable for a second password (the CA's, when signing). */
    static final String SECOND_PASSWORD_ENV = "VECTOLYN_KEYTOOL_SRCPASS";

    /** Clearly below {@code ClusterServer.JOIN_TIMEOUT}, so a slow signing run ends before the join's deadline. */
    private static final int TIMEOUT_SECONDS = 30;
    private static final int OUTPUT_LIMIT = 16 * 1024;

    private Keytool() {
    }

    /** Whether this Java has a keytool. */
    static boolean available() {
        return Files.isRegularFile(path());
    }

    /**
     * Runs keytool.
     *
     * @param arguments  the arguments after {@code keytool}
     * @param passwords  environment variables to set, name to password
     * @throws IOException if keytool is missing, fails or does not end within a minute; the message
     *                     carries the first line of its output, with control characters replaced
     */
    static void run(List<String> arguments, Map<String, Secret> passwords) throws IOException {
        Path keytool = path();
        if (!Files.isRegularFile(keytool)) {
            throw new IOException("keytool is missing from this Java installation (" + keytool.getFileName()
                    + "). The cluster needs a full JDK or JRE that ships keytool.");
        }
        List<String> command = new ArrayList<>();
        command.add(keytool.toString());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        // Nothing of the cloud's own JVM options (an agent, a class path) reaches the process that
        // sees the passwords. English output, so a failure reads the same on every system.
        environment.remove("_JAVA_OPTIONS");
        environment.remove("JDK_JAVA_OPTIONS");
        environment.remove("CLASSPATH");
        environment.put("JAVA_TOOL_OPTIONS", "-Duser.language=en -Duser.country=US");
        passwords.forEach((name, secret) -> environment.put(name, secret.reveal()));
        Process process = builder.start();
        // No input: a keytool that would ask for something reads end of file and fails at once.
        process.getOutputStream().close();
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        // The output is drained on its own thread, so the time limit holds even for a keytool that
        // hangs without a word, and a long output cannot block it on a full pipe.
        Thread reader = Thread.ofVirtual().name("vectolyn-keytool-output").start(() -> drain(process, collected));
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("keytool did not finish within " + TIMEOUT_SECONDS + " seconds");
            }
            reader.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while keytool ran", e);
        }
        String output;
        synchronized (collected) {
            output = collected.toString(StandardCharsets.UTF_8);
        }
        if (process.exitValue() != 0) {
            String line = output.lines().filter(l -> !l.startsWith("Picked up JAVA_TOOL_OPTIONS"))
                    .findFirst().orElse("").replaceAll("\\p{Cntrl}", "?");
            throw new IOException("keytool failed with exit code " + process.exitValue() + ": "
                    + line.substring(0, Math.min(200, line.length())));
        }
    }

    /** Reads the whole output and keeps its first 16 KiB. */
    private static void drain(Process process, ByteArrayOutputStream collected) {
        byte[] buffer = new byte[4096];
        try (InputStream in = process.getInputStream()) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                synchronized (collected) {
                    int room = OUTPUT_LIMIT - collected.size();
                    if (room > 0) {
                        collected.write(buffer, 0, Math.min(room, read));
                    }
                }
            }
        } catch (IOException e) {
            // the process ended or was destroyed
        }
    }

    private static Path path() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "keytool.exe" : "keytool";
        return Path.of(System.getProperty("java.home"), "bin", name);
    }
}
