package net.vectolyn.cloud.node;

import java.io.IOException;
import java.util.Map;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.secret.EnvFormatException;

/**
 * Entry point of the node. {@code cloud-launcher} loads this class by name once the Java version,
 * the root check and the arguments have passed, and calls {@link #start(Map)} on its own thread
 * with the node's class loader as context class loader.
 *
 * <p>{@code start} returns once the node is running. A non-daemon thread keeps the JVM alive until
 * the node is closed, which a shutdown hook does on Ctrl+C, {@code SIGTERM} or a normal JVM exit:
 * every service gets its stop command first.
 */
public final class NodeMain {

    private static volatile CloudNode current;

    private NodeMain() {
    }

    /**
     * Starts the node.
     *
     * @param settings the launcher settings, see {@code docs/cloud/launcher.md}
     * @throws IOException        if the cloud home cannot be read or written
     * @throws ConfigException    if the configuration is invalid; the message lists every error
     * @throws EnvFormatException if {@code secrets/vectolyn.env} is malformed
     * @throws IllegalArgumentException if a setting is missing or has the wrong form
     * @throws IllegalStateException if a node already runs in this JVM
     */
    public static void start(Map<String, String> settings) throws IOException, ConfigException, EnvFormatException {
        NodeSettings parsed = NodeSettings.parse(settings);
        installLogging();
        for (String line : StartupReport.banner(parsed.launcherVersion(), parsed.home())) {
            System.out.println(line);
        }
        System.out.flush();
        start(parsed, options -> { });
    }

    /**
     * Replaces the default console handler of {@code java.util.logging} (two lines per record,
     * localized level names such as {@code INFORMATION} or {@code WARNUNG}, UTF-8 bytes on any
     * console) with one in the cloud's log format on {@code System.out}, until the console takes
     * over ({@code docs/cloud/node.md#start-output}).
     */
    static void installLogging() {
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        for (java.util.logging.Handler handler : root.getHandlers()) {
            if (handler instanceof java.util.logging.ConsoleHandler
                    || handler instanceof net.vectolyn.cloud.console.LogHandlers.StreamHandler) {
                root.removeHandler(handler);
            }
        }
        root.addHandler(net.vectolyn.cloud.console.LogHandlers.stream(System.out));
    }

    /**
     * Opens and starts a node with replaced parts and registers the shutdown hook that closes it
     * on Ctrl+C, {@code SIGTERM} or {@code docker stop}. {@link #start(Map)} with the real parts;
     * the signal test with test parts.
     *
     * @param parsed the settings
     * @param customize see {@link CloudNode.Options}
     * @return the running node
     * @throws IOException        if the cloud home cannot be read or written
     * @throws ConfigException    if the configuration is invalid
     * @throws EnvFormatException if {@code secrets/vectolyn.env} is malformed
     */
    static CloudNode start(NodeSettings parsed, java.util.function.Consumer<CloudNode.Options> customize)
            throws IOException, ConfigException, EnvFormatException {
        synchronized (NodeMain.class) {
            if (current != null) {
                throw new IllegalStateException("a node already runs in this JVM");
            }
            CloudNode node = CloudNode.open(parsed, customize);
            // The hook comes before the start: setting up the database and starting the servers
            // can take minutes, and a SIGTERM or Ctrl+C in that time must still stop what has
            // been started instead of leaving servers and MariaDB running without the cloud.
            Thread hook = new Thread(node::close, "vectolyn-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                node.start();
            } catch (IOException | RuntimeException e) {
                node.close();
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException shuttingDown) {
                    // The JVM is ending already; the hook finds the node closed.
                }
                throw e;
            }
            current = node;
            return node;
        }
    }

    /**
     * The node started by {@link #start(Map)} in this JVM, for code that runs inside it (console,
     * setup assistant).
     *
     * @return the node, or {@code null} before the start
     */
    public static CloudNode current() {
        return current;
    }
}
