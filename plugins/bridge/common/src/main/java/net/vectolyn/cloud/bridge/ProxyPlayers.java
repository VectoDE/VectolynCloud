package net.vectolyn.cloud.bridge;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The players of the proxy, as {@link ProxyBridge} needs them to carry out a transfer or a network
 * kick. The
 * Velocity bridge implements it on {@code ProxyServer}.
 */
public interface ProxyPlayers {

    /**
     * The backend a player is on.
     *
     * @param player the player
     * @return the server name, empty if the player is not online or not on a backend
     */
    Optional<String> currentServer(UUID player);

    /**
     * The language of the player's client.
     *
     * @param player the player
     * @return the locale, or {@code null} if the client has not sent it
     */
    Locale locale(UUID player);

    /**
     * Shows the player a line and connects them to another backend. Must not block.
     *
     * @param player  the player
     * @param server  the server name on the proxy
     * @param message the line to show first
     */
    void move(UUID player, String server, String message);

    /**
     * Disconnects the player from the proxy, and so from the network. Must not block.
     *
     * @param player the player
     * @param reason the plain text the player reads
     * @return whether the player was online
     */
    boolean disconnect(UUID player, String reason);
}
