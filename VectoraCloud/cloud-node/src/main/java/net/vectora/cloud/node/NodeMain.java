package net.vectora.cloud.node;

import java.io.IOException;
import java.util.Map;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.secret.EnvFormatException;

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
     * @throws EnvFormatException if {@code secrets/vectora.env} is malformed
     * @throws IllegalArgumentException if a setting is missing or has the wrong form
     * @throws IllegalStateException if a node already runs in this JVM
     */
    public static void start(Map<String, String> settings) throws IOException, ConfigException, EnvFormatException {
        NodeSettings parsed = NodeSettings.parse(settings);
        synchronized (NodeMain.class) {
            if (current != null) {
                throw new IllegalStateException("a node already runs in this JVM");
            }
            CloudNode node = CloudNode.open(parsed, builder -> { });
            try {
                node.start();
            } catch (IOException | RuntimeException e) {
                node.close();
                throw e;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(node::close, "vectora-shutdown"));
            current = node;
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
