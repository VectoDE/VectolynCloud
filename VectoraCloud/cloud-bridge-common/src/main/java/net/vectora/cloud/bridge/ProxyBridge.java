package net.vectora.cloud.bridge;

import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.vectora.cloud.client.CloudClientEvent;
import net.vectora.cloud.client.CloudClientListener;
import net.vectora.protocol.message.RegisterServer;
import net.vectora.protocol.message.ServerCapacity;
import net.vectora.protocol.message.TransferPlayer;
import net.vectora.protocol.message.UnregisterServer;

/**
 * Proxy side of a bridge, without any Velocity type. The Velocity plugin passes the proxy's events
 * in; this class keeps the server list in step with the cloud, reports checked player events,
 * picks the backend a player who enters a group goes to, and moves players the cloud asks for.
 *
 * <p>Messages from the cloud arrive on the client's event thread ({@link #onEvent}). Player events
 * arrive on proxy threads and only touch memory; the network part goes through the
 * {@link BridgeSender}.
 */
public final class ProxyBridge implements CloudClientListener {

    private static final System.Logger LOG = System.getLogger(ProxyBridge.class.getName());

    private final BridgeSender sender;
    private final ProxyServers servers;
    private final ProxyPlayers players;
    private final ServerRegistry registry;
    private final PlayerTracker tracker;
    private final ServerLoads loads;

    /** Creates the bridge for a proxy in {@code role} whose server list is {@code servers}. */
    public ProxyBridge(BridgeSender sender, ProxyServers servers, ProxyPlayers players, ProxyRole role) {
        this(sender, servers, players, role, new ServerLoads());
    }

    /** As above, with the load directory given (tests: its own clock). */
    ProxyBridge(BridgeSender sender, ProxyServers servers, ProxyPlayers players, ProxyRole role, ServerLoads loads) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.players = Objects.requireNonNull(players, "players");
        this.registry = new ServerRegistry(servers, role);
        this.tracker = new PlayerTracker(servers::exists);
        this.loads = Objects.requireNonNull(loads, "loads");
    }

    @Override
    public void onEvent(CloudClientEvent event) {
        if (!(event instanceof CloudClientEvent.MessageReceived(var message))) {
            return;
        }
        switch (message) {
            case RegisterServer register -> {
                ServerRegistry.Outcome outcome = registry.register(register);
                if (!outcome.refused()) {
                    loads.registered(register.name().value(), register.group().value());
                }
                Level level = outcome.refused() ? Level.WARNING : Level.INFO;
                LOG.log(level, "Server {0} ({1}:{2}) from VectoraCloud: {3}", register.name().value(), register.host(),
                        String.valueOf(register.port()), outcome);
            }
            case UnregisterServer unregister -> {
                ServerRegistry.Outcome outcome = registry.unregister(unregister);
                loads.removed(unregister.name().value());
                LOG.log(Level.INFO, "Removal of server {0} from VectoraCloud: {1}", unregister.name().value(), outcome);
            }
            case ServerCapacity capacity -> {
                if (!loads.capacity(capacity.name().value(), capacity.slots(), capacity.open())) {
                    LOG.log(Level.DEBUG, "Capacity for unknown server {0} ignored", capacity.name().value());
                }
            }
            case TransferPlayer transfer -> transfer(transfer);
            default -> {
                // Everything else is for the client itself.
            }
        }
    }

    /**
     * Carries out a transfer the cloud asked for, only if it fits: the player is on a backend of
     * the same group as the target, the target is another backend that is listed and open. The
     * player sees why first. Nothing is sent back: the {@code PlayerSwitch} of the move tells the
     * cloud that it worked.
     */
    private void transfer(TransferPlayer transfer) {
        String target = transfer.to().value();
        Optional<String> current = players.currentServer(transfer.uuid());
        Optional<String> group = loads.groupOf(target);
        if (current.isEmpty() || group.isEmpty() || current.get().equals(target) || !servers.exists(target)
                || !loads.isOpen(target) || !group.equals(loads.groupOf(current.get()))) {
            LOG.log(Level.DEBUG, "Transfer to {0} not carried out: it does not fit the player''s server", target);
            return;
        }
        players.move(transfer.uuid(), target, TransferMessages.text(transfer.reason(), players.locale(transfer.uuid())));
    }

    /**
     * The backend a player joining the network goes to: the backend of the same group as the
     * proxy's own choice with the lowest load.
     *
     * @param player       the player
     * @param proxysChoice the server the proxy would pick (first entry of its try list or a forced host)
     * @return another backend, or empty to keep the proxy's choice
     */
    public Optional<String> initialServer(UUID player, String proxysChoice) {
        return enter(player, proxysChoice, Set.of());
    }

    /**
     * The backend a kicked player is sent to when the proxy redirects them into a group (lobby
     * fallback): the backend of the same group as the proxy's target with the lowest load, never
     * the one the player was kicked from.
     *
     * @param player     the player
     * @param kickedFrom the backend that kicked the player
     * @param target     the server the proxy would redirect to
     * @return another backend, or empty to keep the proxy's target
     */
    public Optional<String> redirect(UUID player, String kickedFrom, String target) {
        return enter(player, target, Set.of(kickedFrom));
    }

    private Optional<String> enter(UUID player, String proxysChoice, Set<String> exclude) {
        return loads.groupOf(proxysChoice)
                .flatMap(group -> loads.choose(player, group, exclude, servers::exists, servers::playersOn));
    }

    /** The proxy is listening for players. */
    public void ready() {
        sender.markReady();
    }

    /**
     * A backend accepted the player. Call from the proxy's "server connected" event, with the
     * platform's player object as {@code session}.
     */
    public void playerConnected(Object session, UUID uuid, String name, String server) {
        loads.arrived(uuid);
        // Queued under the tracker's lock, so events of different threads keep the tracker's order.
        // Submitting never blocks.
        synchronized (tracker) {
            tracker.connected(session, uuid, name, server).forEach(sender::send);
        }
    }

    /** The player left the proxy. {@code session} is the player object of the login that ended. */
    public void playerDisconnected(Object session, UUID uuid) {
        loads.arrived(uuid);
        synchronized (tracker) {
            tracker.disconnected(session, uuid).ifPresent(sender::send);
        }
    }

    /** Queues a status report. */
    public void status(int playersOnline, int maxPlayers) {
        sender.reportStatus(StatusSnapshot.proxy(playersOnline, maxPlayers));
    }

    /** The servers added on the cloud's behalf. */
    public ServerRegistry registry() {
        return registry;
    }

    /** The reported player state. */
    public PlayerTracker tracker() {
        return tracker;
    }

    /** Groups, slots and loads of the backends. */
    public ServerLoads loads() {
        return loads;
    }
}
