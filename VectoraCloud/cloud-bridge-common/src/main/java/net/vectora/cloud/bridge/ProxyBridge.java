package net.vectora.cloud.bridge;

import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.UUID;
import net.vectora.cloud.client.CloudClientEvent;
import net.vectora.cloud.client.CloudClientListener;
import net.vectora.protocol.message.RegisterServer;
import net.vectora.protocol.message.UnregisterServer;

/**
 * Proxy side of a bridge, without any Velocity type. The Velocity plugin passes the proxy's events
 * in; this class keeps the server list in step with the cloud and reports checked player events.
 *
 * <p>Messages from the cloud arrive on the client's event thread ({@link #onEvent}). Player events
 * arrive on proxy threads and only touch memory; the network part goes through the
 * {@link BridgeSender}.
 */
public final class ProxyBridge implements CloudClientListener {

    private static final System.Logger LOG = System.getLogger(ProxyBridge.class.getName());

    private final BridgeSender sender;
    private final ServerRegistry registry;
    private final PlayerTracker tracker;

    /** Creates the bridge for a proxy in {@code role} whose server list is {@code servers}. */
    public ProxyBridge(BridgeSender sender, ProxyServers servers, ProxyRole role) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.registry = new ServerRegistry(servers, role);
        this.tracker = new PlayerTracker(servers::exists);
    }

    @Override
    public void onEvent(CloudClientEvent event) {
        if (!(event instanceof CloudClientEvent.MessageReceived(var message))) {
            return;
        }
        if (message instanceof RegisterServer register) {
            ServerRegistry.Outcome outcome = registry.register(register);
            Level level = outcome.refused() ? Level.WARNING : Level.INFO;
            LOG.log(level, "Server {0} ({1}:{2}) from VectoraCloud: {3}", register.name().value(), register.host(),
                    String.valueOf(register.port()), outcome);
        } else if (message instanceof UnregisterServer unregister) {
            ServerRegistry.Outcome outcome = registry.unregister(unregister);
            LOG.log(Level.INFO, "Removal of server {0} from VectoraCloud: {1}", unregister.name().value(), outcome);
        }
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
        // Queued under the tracker's lock, so events of different threads keep the tracker's order.
        // Submitting never blocks.
        synchronized (tracker) {
            tracker.connected(session, uuid, name, server).forEach(sender::send);
        }
    }

    /** The player left the proxy. {@code session} is the player object of the login that ended. */
    public void playerDisconnected(Object session, UUID uuid) {
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
}
