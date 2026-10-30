package net.vectolyn.cloud.config.model;

/**
 * Second Velocity proxy in {@code bungeeguard} mode, in front of the 1.8.8 groups (PandaSpigot).
 *
 * @param enabled whether the cloud runs it
 * @param port    its own Java port
 */
public record LegacyProxyConfig(boolean enabled, int port) {

    /** Off, port 25564. */
    public static LegacyProxyConfig defaults() {
        return new LegacyProxyConfig(false, 25564);
    }
}
