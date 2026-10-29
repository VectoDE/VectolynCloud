package net.vectora.cloud.bridge;

/**
 * The server list of the proxy, as {@link ServerRegistry} needs it. The Velocity bridge implements
 * it on {@code ProxyServer}.
 */
public interface ProxyServers {

    /** Whether the server is in the proxy's own configuration ({@code velocity.toml}). */
    boolean isConfigured(String name);

    /** Whether the proxy has a server of this name, configured or registered at runtime. */
    boolean exists(String name);

    /** Adds a server. May resolve {@code host} if it is not an IP literal. */
    void register(String name, String host, int port);

    /** Removes a server added with {@link #register}. */
    void unregister(String name);
}
