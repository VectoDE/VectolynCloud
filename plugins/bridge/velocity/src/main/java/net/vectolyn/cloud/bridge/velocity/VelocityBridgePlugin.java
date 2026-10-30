package net.vectolyn.cloud.bridge.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.EventTask;
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
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.vectolyn.cloud.bridge.BridgeConnection;
import net.vectolyn.cloud.bridge.BridgeSender;
import net.vectolyn.cloud.bridge.KickOutcome;
import net.vectolyn.cloud.bridge.PoweredBy;
import net.vectolyn.cloud.bridge.ProxyBridge;
import net.vectolyn.cloud.bridge.ProxyRole;
import net.vectolyn.cloud.bridge.TransferMessages;
import net.vectolyn.cloud.client.CloudClientSettings;
import net.vectolyn.protocol.ServiceEnvironment;

/**
 * VectolynCloud bridge for Velocity 4.3, in the main proxy and in the legacy proxy. Thin adapter
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
 *   <li>Kicks: a kicked player goes to another server of the group or to the fallback group,
 *       except after a ban, the whitelist, a kick marked by our moderation or a network kick a
 *       backend reported ({@code ProxyBridge.kickedOrMarked}).</li>
 *   <li>Network kicks: a backend's {@code NetworkKick} disconnects the player
 *       ({@code ProxyBridge.onEvent}).</li>
 *   <li>Closed servers: a server the cloud is emptying takes no player who switches to it, except
 *       with the bypass permission ({@code ProxyBridge.mayConnect}).</li>
 *   <li>{@code /version}: answered by the proxy with the cloud's version while
 *       {@code branding.powered-by} is on ({@link PoweredBy}).</li>
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
     *   <li>Every kick except a ban, the whitelist and a kick marked by our moderation
     *       ({@code ProxyBridge.kicked}): the player goes to another server of the group, else to
     *       the fallback group, else is disconnected with the original reason. A player who still
     *       is on another server only moves inside the group and otherwise stays. After a kick
     *       with a reason of its own (not a stop, crash or full server) the player reads that
     *       reason as a chat line once moved.</li>
     *   <li>A ban, the whitelist and a marked kick stay as the proxy decided; only a redirect into
     *       a group goes to its emptiest server, never back to one already tried.</li>
     *   <li>A kick the backend reported as a network kick ({@code NetworkKick}: a {@code /kick} of
     *       an entitled sender or a kick of the moderation) disconnects the player from the
     *       network. A kick that may be such a {@code /kick} waits up to 300 ms for the report
     *       ({@code ProxyBridge.kickedOrMarked}).</li>
     * </ul>
     */
    @Subscribe
    public EventTask onKickedFromServer(KickedFromServerEvent event) {
        ProxyBridge target = bridge;
        if (target == null) {
            return null;
        }
        Player player = event.getPlayer();
        String kickedFrom = event.getServer().getServerInfo().getName();
        boolean staysConnected = player.getCurrentServer()
                .map(current -> !current.getServerInfo().getName().equals(kickedFrom)).orElse(false);
        CompletableFuture<KickOutcome> outcome = target.kickedOrMarked(player.getUniqueId(), kickedFrom,
                KickReasons.of(event.getServerKickReason()), staysConnected);
        if (outcome.isDone()) {
            apply(event, target, kickedFrom, outcome.join());
            return null;
        }
        // A possible /kick waits briefly for the backend's network kick; Velocity holds the event.
        return EventTask.resumeWhenComplete(outcome.handle((result, error) -> {
            if (error != null) {
                LOG.log(System.Logger.Level.WARNING, "A kick could not be handled: {0}", error.toString());
            } else {
                apply(event, target, kickedFrom, result);
            }
            return null;
        }));
    }

    /** Carries out what the bridge decided for a kick. */
    private void apply(KickedFromServerEvent event, ProxyBridge target, String kickedFrom, KickOutcome outcome) {
        switch (outcome.action()) {
            case MOVE -> {
                Optional<RegisteredServer> server = proxy.getServer(outcome.server());
                if (server.isPresent()) {
                    Component notice = Component.text(outcome.message(), NamedTextColor.YELLOW);
                    // Velocity sends the redirect's message as a chat line once the move succeeded.
                    Component message = outcome.showReason() ? event.getServerKickReason().orElse(notice) : notice;
                    event.setResult(KickedFromServerEvent.RedirectPlayer.create(server.get(), message));
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

    /**
     * A redirect of the proxy into a group goes to the group's emptiest server instead. It counts
     * for the kick guard: servers already tried in the incident are skipped, and a player who was
     * moved too often, or whose only target was tried already, is disconnected
     * ({@code ProxyBridge.redirect}).
     */
    private void rebalance(KickedFromServerEvent event, ProxyBridge target, String kickedFrom) {
        if (!(event.getResult() instanceof KickedFromServerEvent.RedirectPlayer redirect)) {
            return;
        }
        KickOutcome outcome = target.redirect(event.getPlayer().getUniqueId(), kickedFrom,
                redirect.getServer().getServerInfo().getName());
        switch (outcome.action()) {
            case MOVE -> proxy.getServer(outcome.server()).ifPresent(server -> event.setResult(
                    KickedFromServerEvent.RedirectPlayer.create(server, redirect.getMessageComponent())));
            case DISCONNECT -> disconnectWithReason(event, outcome);
            case KEEP -> {
                // Velocity's own target, which the guard has counted.
            }
        }
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
        String fallbackGroup = ProxyBridge.fallbackGroup(System::getenv);
        sender = BridgeConnection.open(settings.get(), created -> {
            ProxyBridge proxyBridge = new ProxyBridge(created, servers, new VelocityPlayers(proxy), role, fallbackGroup);
            bridge = proxyBridge;
            return proxyBridge;
        });
        statusTask = proxy.getScheduler().buildTask(this, this::reportStatus).delay(STATUS_PERIOD)
                .repeat(STATUS_PERIOD).schedule();
        registerVersionCommand();
        // Explicit registration: the handler needs nothing from the event.
        proxy.getEventManager().register(this, ProxyShutdownEvent.class, shutdown -> disconnect());
        return bridge;
    }

    /**
     * Registers {@value PoweredBy#COMMAND} while {@code branding.powered-by} is on
     * ({@value ServiceEnvironment#POWERED_BY}). Off, nothing is registered and the command reaches
     * the backend as before.
     */
    private void registerVersionCommand() {
        if (!PoweredBy.enabled(System::getenv)) {
            LOG.log(System.Logger.Level.INFO, "/{0} is left to the backends (branding.powered-by is off)",
                    PoweredBy.COMMAND);
            return;
        }
        CommandManager commands = proxy.getCommandManager();
        commands.register(commands.metaBuilder(PoweredBy.COMMAND).plugin(this).build(),
                new VersionCommand(PoweredBy.version(System::getenv)));
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
