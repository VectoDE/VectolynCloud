package net.vectora.cloud.bridge.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ListenerBoundEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.network.ListenerType;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.vectora.cloud.bridge.BridgeConnection;
import net.vectora.cloud.bridge.BridgeSender;
import net.vectora.cloud.bridge.KickOutcome;
import net.vectora.cloud.bridge.ProxyBridge;
import net.vectora.cloud.bridge.ProxyRole;
import net.vectora.cloud.bridge.TransferMessages;
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
 *   <li>Kicks: a player whose server stopped, crashed or is full goes to another server of the
 *       group or to the lobby ({@code ProxyBridge.kicked}).</li>
 *   <li>Closed servers: a server the cloud is emptying takes no player who switches to it, except
 *       with the bypass permission ({@code ProxyBridge.mayConnect}).</li>
 * </ul>
 */
public final class VelocityBridgePlugin {

    /** How often the proxy reports its status. */
    public static final Duration STATUS_PERIOD = Duration.ofSeconds(5);

    private static final System.Logger LOG = System.getLogger(VelocityBridgePlugin.class.getName());

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

    /**
     * A player joins the network: the proxy's first choice (try list or forced host) is replaced
     * by the backend of the same group with the lowest load.
     */
    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        ProxyBridge target = bridge;
        if (target == null || event.getInitialServer().isEmpty()) {
            return;
        }
        String choice = event.getInitialServer().get().getServerInfo().getName();
        target.initialServer(event.getPlayer().getUniqueId(), choice)
                .flatMap(proxy::getServer)
                .ifPresent(event::setInitialServer);
    }

    /**
     * A backend kicked the player or refused the connect.
     *
     * <ul>
     *   <li>A stop, crash, lost connection, timeout or full server ({@code ProxyBridge.kicked}):
     *       the player goes to another server of the group, else to the lobby, else is
     *       disconnected with the original reason. A player who still is on another server only
     *       moves inside the group and otherwise stays.</li>
     *   <li>Any other kick (ban, whitelist, a kick someone meant) stays as the proxy decided; only a
     *       redirect into a group goes to its emptiest server, never back to the one that
     *       kicked.</li>
     * </ul>
     */
    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        ProxyBridge target = bridge;
        if (target == null) {
            return;
        }
        Player player = event.getPlayer();
        String kickedFrom = event.getServer().getServerInfo().getName();
        boolean staysConnected = player.getCurrentServer()
                .map(current -> !current.getServerInfo().getName().equals(kickedFrom)).orElse(false);
        KickOutcome outcome = target.kicked(player.getUniqueId(), kickedFrom,
                KickReasons.of(event.getServerKickReason()), staysConnected);
        switch (outcome.action()) {
            case MOVE -> {
                Optional<RegisteredServer> server = proxy.getServer(outcome.server());
                if (server.isPresent()) {
                    event.setResult(KickedFromServerEvent.RedirectPlayer.create(server.get(),
                            Component.text(outcome.message(), NamedTextColor.YELLOW)));
                } else {
                    disconnectWithReason(event, outcome);
                }
            }
            case DISCONNECT -> disconnectWithReason(event, outcome);
            case KEEP -> rebalance(event, target, kickedFrom);
        }
    }

    /** Disconnects with the backend's reason, else the proxy's own disconnect, else the bridge's text. */
    private static void disconnectWithReason(KickedFromServerEvent event, KickOutcome outcome) {
        Component reason = event.getServerKickReason().orElseGet(() ->
                event.getResult() instanceof KickedFromServerEvent.DisconnectPlayer disconnect
                        ? disconnect.getReasonComponent()
                        : Component.text(outcome.message(), NamedTextColor.RED));
        event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reason));
    }

    /** A redirect of the proxy into a group goes to the group's emptiest server instead. */
    private void rebalance(KickedFromServerEvent event, ProxyBridge target, String kickedFrom) {
        if (!(event.getResult() instanceof KickedFromServerEvent.RedirectPlayer redirect)) {
            return;
        }
        target.redirect(event.getPlayer().getUniqueId(), kickedFrom, redirect.getServer().getServerInfo().getName())
                .flatMap(proxy::getServer)
                .ifPresent(server -> event.setResult(
                        KickedFromServerEvent.RedirectPlayer.create(server, redirect.getMessageComponent())));
    }

    /**
     * A player wants onto a backend (a switch, {@code /server}, a redirect): a server the cloud
     * closed is refused with a short line, unless the player has
     * {@value ProxyBridge#BYPASS_CLOSED_PERMISSION}. The first server of a login is let through.
     */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        ProxyBridge target = bridge;
        Optional<RegisteredServer> server = event.getResult().getServer();
        if (target == null || !event.getResult().isAllowed() || server.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        if (!target.mayConnect(server.get().getServerInfo().getName(),
                player.hasPermission(ProxyBridge.BYPASS_CLOSED_PERMISSION), player.getCurrentServer().isPresent())) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            player.sendMessage(Component.text(TransferMessages.closed(player.getEffectiveLocale()), NamedTextColor.RED));
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
        String platform = System.getenv(ProxyRole.ENV_PLATFORM);
        ProxyRole role = ProxyRole.fromPlatform(platform);
        LOG.log(ProxyRole.isProxyPlatform(platform) ? System.Logger.Level.INFO : System.Logger.Level.WARNING,
                ProxyRole.describe(platform));
        VelocityServers servers = new VelocityServers(proxy);
        sender = BridgeConnection.open(settings.get(), created -> {
            ProxyBridge proxyBridge = new ProxyBridge(created, servers, new VelocityPlayers(proxy), role);
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
