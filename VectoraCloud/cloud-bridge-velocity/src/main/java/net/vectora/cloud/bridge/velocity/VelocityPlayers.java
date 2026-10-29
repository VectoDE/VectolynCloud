package net.vectora.cloud.bridge.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.vectora.cloud.bridge.ProxyPlayers;

/** {@link ProxyPlayers} on Velocity's players. */
final class VelocityPlayers implements ProxyPlayers {

    private static final System.Logger LOG = System.getLogger(VelocityPlayers.class.getName());

    private final ProxyServer proxy;

    VelocityPlayers(ProxyServer proxy) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
    }

    @Override
    public Optional<String> currentServer(UUID player) {
        return proxy.getPlayer(player).flatMap(Player::getCurrentServer)
                .map(connection -> connection.getServerInfo().getName());
    }

    @Override
    public Locale locale(UUID player) {
        return proxy.getPlayer(player).map(Player::getEffectiveLocale).orElse(null);
    }

    @Override
    public void move(UUID player, String server, String message) {
        Optional<Player> online = proxy.getPlayer(player);
        Optional<RegisteredServer> target = proxy.getServer(server);
        if (online.isEmpty() || target.isEmpty()) {
            return;
        }
        online.get().sendMessage(Component.text(message, NamedTextColor.YELLOW));
        // Asynchronous; a failure leaves the player where they are, and the cloud then keeps the server.
        online.get().createConnectionRequest(target.get()).connectWithIndication().exceptionally(error -> {
            LOG.log(System.Logger.Level.WARNING, "A player could not be moved to {0}: {1}", server, error.toString());
            return false;
        });
    }
}
