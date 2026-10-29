package net.vectora.cloud.bridge.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ListenerBoundEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.network.ListenerType;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.Optional;
import net.vectora.cloud.bridge.BridgeConnection;
import net.vectora.cloud.bridge.BridgeSender;
import net.vectora.cloud.bridge.ProxyBridge;
import net.vectora.cloud.bridge.ProxyRole;
import net.vectora.cloud.client.CloudClientSettings;

/**
 * VectoraCloud bridge for Velocity 4.3, in the main proxy and in the legacy proxy. Thin adapter
 * over {@link ProxyBridge}: it passes the proxy's events in, the bridge checks them and queues the
 * cloud calls on its own thread.
 *
 * <ul>
 *   <li>Role: {@value ProxyRole#ENV_PLATFORM} {@code = VELOCITY_LEGACY} makes it the legacy
 *       proxy, which only takes 1.8.8 servers; anything else is the main proxy.</li>
 *   <li>Connect and ready: when the Minecraft listener is bound, that is when players can
 *       connect.</li>
 *   <li>Status: every {@link #STATUS_PERIOD} with players and the shown player limit.</li>
 *   <li>Player events: join on the first backend, switch, quit, checked against the reported
 *       state ({@code PlayerTracker}).</li>
 * </ul>
 */
public final class VelocityBridgePlugin {

    /** How often the proxy reports its status. */
    public static final Duration STATUS_PERIOD = Duration.ofSeconds(5);

    private final ProxyServer proxy;
    private volatile BridgeSender sender;
    private volatile ProxyBridge bridge;
    private volatile ScheduledTask statusTask;

    /** Created by Velocity. */
    @Inject
    public VelocityBridgePlugin(ProxyServer proxy) {
        this.proxy = proxy;
    }

    /**
     * The proxy accepts players once its Minecraft listener is bound. The first time, the bridge
     * connects to the cloud (if the proxy was started by it) and signals ready; later binds (after
     * a config reload) only repeat the ready signal.
     */
    @Subscribe
    public void onListenerBound(ListenerBoundEvent event) {
        if (event.getListenerType() != ListenerType.MINECRAFT) {
            return;
        }
        ProxyBridge target = bridge;
        if (target == null) {
            target = connect();
        }
        if (target != null) {
            target.ready();
        }
    }

    /** A backend accepted the player. */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        ProxyBridge target = bridge;
        if (target != null) {
            target.playerConnected(event.getPlayer(), event.getPlayer().getUniqueId(), event.getPlayer().getUsername(),
                    event.getServer().getServerInfo().getName());
        }
    }

    /** The player left the proxy. */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        ProxyBridge target = bridge;
        if (target != null) {
            target.playerDisconnected(event.getPlayer(), event.getPlayer().getUniqueId());
        }
    }

    private synchronized ProxyBridge connect() {
        if (bridge != null) {
            return bridge;
        }
        Optional<CloudClientSettings> settings = BridgeConnection.settings();
        if (settings.isEmpty()) {
            return null;
        }
        ProxyRole role = ProxyRole.fromPlatform(System.getenv(ProxyRole.ENV_PLATFORM));
        VelocityServers servers = new VelocityServers(proxy);
        sender = BridgeConnection.open(settings.get(), created -> {
            ProxyBridge proxyBridge = new ProxyBridge(created, servers, role);
            bridge = proxyBridge;
            return proxyBridge;
        });
        statusTask = proxy.getScheduler().buildTask(this, this::reportStatus).delay(STATUS_PERIOD)
                .repeat(STATUS_PERIOD).schedule();
        // Explicit registration: the handler needs nothing from the event.
        proxy.getEventManager().register(this, ProxyShutdownEvent.class, shutdown -> disconnect());
        return bridge;
    }

    /** Says goodbye to the cloud. */
    private void disconnect() {
        ScheduledTask task = statusTask;
        if (task != null) {
            task.cancel();
        }
        BridgeSender target = sender;
        if (target != null) {
            target.close();
        }
    }

    private void reportStatus() {
        ProxyBridge target = bridge;
        if (target != null) {
            target.status(proxy.getPlayerCount(), proxy.getConfiguration().getShowMaxPlayers());
        }
    }
}
