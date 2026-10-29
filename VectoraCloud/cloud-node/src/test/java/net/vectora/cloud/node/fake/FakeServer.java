package net.vectora.cloud.node.fake;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stand-in for a Minecraft server, packed into a jar by {@link FakeServerJar} and started as a real
 * process by the tests. Uses only the JDK and has no nested classes, so the jar holds exactly this
 * one class file.
 *
 * <p>Behaviour, chosen with {@code -Dfake.mode=...} in the group's {@code jvm-flags}:
 * <ul>
 *   <li>{@code normal}: prints a Paper-like start log and {@code Done (…)!}, echoes every console
 *       line as {@code > line}, and on {@code stop} (Paper) or {@code shutdown} (Velocity) writes
 *       {@code stopped-cleanly} and exits 0;</li>
 *   <li>{@code crash}: exits with code 3 right after starting;</li>
 *   <li>{@code ignore-stop}: never reacts to {@code stop}; ends on the termination request;</li>
 *   <li>{@code ignore-term}: like {@code ignore-stop}, and its shutdown hook blocks, so only the
 *       forced end stops it (on Linux and macOS).</li>
 * </ul>
 * With the cloud client, a console line {@code player join|switch|quit ...} sends a player event
 * like a proxy bridge, and received {@code RegisterServer}/{@code UnregisterServer} are printed.
 * It also prints {@code VECTORA_SERVICE_PLATFORM}, whether {@code VECTORA_LEGACY_FORWARDING_SECRET}
 * is set (never its value), the service token (which the node must mask in the log), writes it to
 * {@code token.txt} for the test, prints every line of the files named in {@code -Dfake.echo}
 * (to prove secrets in rendered files are masked), and creates {@code world/level.dat} like a
 * real server creating its world.
 *
 * <p>With {@code -Dfake.cloud=true}, or {@code fake.cloud=true} in the jar's defaults (a jar from
 * {@link FakeServerJar#writeConnected}, for groups whose JVM flags the test does not write), it connects to the cloud channel through
 * {@code FakeCloudBridge} (loaded by name, so this class keeps using only the JDK; packed by
 * {@link FakeServerJar#writeWithCloudClient}) and does not print {@code Done (…)!}: it reports
 * readiness only through {@code CloudClient.markReady()}, so the service becomes RUNNING only if
 * the node handles the channel's {@code ServiceReady}.
 */
public final class FakeServer {

    private FakeServer() {
    }

    /**
     * Runs the fake server.
     *
     * @param args command line arguments, printed
     * @throws Exception on any failure, which ends the process with a non-zero code
     */
    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        String mode = System.getProperty("fake.mode", "normal");
        String id = System.getenv("VECTORA_SERVICE_ID");
        String token = System.getenv("VECTORA_SERVICE_TOKEN");
        out.println("[fake] Starting minecraft server " + id + " on port " + System.getenv("VECTORA_SERVICE_PORT"));
        out.println("[fake] args " + String.join(" ", args));
        out.println("[fake] token " + token);
        out.println("[fake] platform " + System.getenv("VECTORA_SERVICE_PLATFORM"));
        out.println("[fake] java " + Runtime.version().feature());
        out.println("[fake] legacy secret " + (System.getenv("VECTORA_LEGACY_FORWARDING_SECRET") == null
                ? "absent" : "present"));
        Files.writeString(Path.of("token.txt"), token == null ? "" : token, StandardCharsets.UTF_8);
        String echo = System.getProperty("fake.echo");
        if (echo != null && Files.isRegularFile(Path.of(echo))) {
            for (String line : Files.readAllLines(Path.of(echo), StandardCharsets.UTF_8)) {
                out.println("[fake] " + echo + ": " + line);
            }
        }
        Files.createDirectories(Path.of("world"));
        if (!Files.exists(Path.of("world", "level.dat"))) {
            Files.writeString(Path.of("world", "level.dat"), "fake world of " + id, StandardCharsets.UTF_8);
        }
        if (mode.equals("crash")) {
            out.println("[fake] crashing on purpose");
            System.exit(3);
        }
        if (mode.equals("ignore-term")) {
            Runtime.getRuntime().addShutdownHook(new Thread(FakeServer::sleepForever));
        }
        AutoCloseable bridge = null;
        if (Boolean.parseBoolean(System.getProperty("fake.cloud", defaults().getProperty("fake.cloud", "false")))) {
            bridge = (AutoCloseable) Class.forName("net.vectora.cloud.node.fake.FakeCloudBridge")
                    .getMethod("connect", PrintStream.class).invoke(null, out);
        } else {
            out.println("[fake] Done (0.042s)! For help, type \"help\"");
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            out.println("> " + line);
            if (line.startsWith("player ") && bridge != null) {
                Class.forName("net.vectora.cloud.node.fake.FakeCloudBridge")
                        .getMethod("command", Object.class, PrintStream.class, String.class)
                        .invoke(null, bridge, out, line);
            }
            if ((line.equals("stop") || line.equals("shutdown")) && mode.equals("normal")) {
                out.println("[fake] Stopping the server");
                if (bridge != null) {
                    bridge.close();
                }
                Files.writeString(Path.of("stopped-cleanly"), "yes", StandardCharsets.UTF_8);
                System.exit(0);
            }
        }
        if (mode.equals("normal")) {
            System.exit(0);
        }
        sleepForever();
    }

    /** {@value FakeServerJar#DEFAULTS} in the jar, written by {@link FakeServerJar#writeConnected}. */
    private static java.util.Properties defaults() throws java.io.IOException {
        java.util.Properties defaults = new java.util.Properties();
        try (java.io.InputStream in = FakeServer.class.getResourceAsStream("/" + FakeServerJar.DEFAULTS)) {
            if (in != null) {
                defaults.load(in);
            }
        }
        return defaults;
    }

    private static void sleepForever() {
        while (true) {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                // Keep sleeping; only the forced end stops this process.
            }
        }
    }
}
