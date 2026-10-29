package net.vectora.cloud.bridge;

import java.lang.System.Logger.Level;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import net.vectora.cloud.client.CloudClientEvent;
import net.vectora.cloud.client.CloudClientListener;
import net.vectora.protocol.GroupName;
import net.vectora.protocol.ServiceEnvironment;
import net.vectora.protocol.message.NetworkKick;
import net.vectora.protocol.message.RegisterServer;
import net.vectora.protocol.message.ServerCapacity;
import net.vectora.protocol.message.TransferPlayer;
import net.vectora.protocol.message.UnregisterServer;

/**
 * Proxy side of a bridge, without any Velocity type. The Velocity plugin passes the proxy's events
 * in; this class keeps the server list in step with the cloud, reports checked player events,
 * picks the backend a player who enters a group goes to, moves players the cloud asks for and takes
 * players off the network whom a backend kicked as a punishment ({@code NetworkKick}).
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
    private final KickGuard guard;
    private final KickMarks marks;
    private final String fallbackGroup;

    /**
     * The group a kicked player goes to when no other server of their own group is free, when the
     * cloud names none ({@value ServiceEnvironment#FALLBACK_GROUP}, from {@code fallback-group} in
     * {@code cloud.yml}).
     */
    public static final String DEFAULT_FALLBACK_GROUP = "lobby";

    /** Lets a player onto a server the cloud closed. */
    public static final String BYPASS_CLOSED_PERMISSION = "vectora.server.bypass-closed";

    /**
     * Creates the bridge for a proxy in {@code role} whose server list is {@code servers}.
     *
     * @param fallbackGroup the group a kicked player goes to when no other server of their own
     *                      group is free, see {@link #fallbackGroup(UnaryOperator)}
     */
    public ProxyBridge(BridgeSender sender, ProxyServers servers, ProxyPlayers players, ProxyRole role,
                       String fallbackGroup) {
        this(sender, servers, players, role, new ServerLoads(), new KickGuard(), new KickMarks(), fallbackGroup);
    }

    /**
     * The fallback group the cloud passed in {@value ServiceEnvironment#FALLBACK_GROUP}. A missing
     * or invalid value (a proxy started by an older node, a hand-made start) gives
     * {@link #DEFAULT_FALLBACK_GROUP}; an invalid one is logged without repeating it.
     *
     * @param environment reads an environment variable, {@code System::getenv}
     * @return a valid group name
     */
    public static String fallbackGroup(UnaryOperator<String> environment) {
        String value = environment.apply(ServiceEnvironment.FALLBACK_GROUP);
        if (value == null || value.isBlank()) {
            return DEFAULT_FALLBACK_GROUP;
        }
        try {
            return new GroupName(value.strip()).value();
        } catch (IllegalArgumentException e) {
            LOG.log(Level.WARNING, "{0} is no valid group name; kicked players fall back to the group {1}",
                    ServiceEnvironment.FALLBACK_GROUP, DEFAULT_FALLBACK_GROUP);
            return DEFAULT_FALLBACK_GROUP;
        }
    }

    /** As above, with the load directory, the kick guard, the marks and the fallback group given (tests). */
    ProxyBridge(BridgeSender sender, ProxyServers servers, ProxyPlayers players, ProxyRole role, ServerLoads loads,
                KickGuard guard, KickMarks marks, String fallbackGroup) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.players = Objects.requireNonNull(players, "players");
        this.registry = new ServerRegistry(servers, role);
        this.tracker = new PlayerTracker(servers::exists);
        this.loads = Objects.requireNonNull(loads, "loads");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.marks = Objects.requireNonNull(marks, "marks");
        this.fallbackGroup = Objects.requireNonNull(fallbackGroup, "fallbackGroup");
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
            case NetworkKick kick -> networkKick(kick);
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
     * A backend took a player off the network ({@code NetworkKick}, passed on by the cloud, which
     * only does so for a backend it registered). Accepted only for a server the cloud listed and
     * only if the player is on it or was kicked from it within {@link KickMarks#WINDOW}. A kick of
     * the player that waits for the mark ({@link #kickedOrMarked}) disconnects them; otherwise the
     * proxy disconnects them now, also if their kick was already caught and they were moved.
     */
    private void networkKick(NetworkKick kick) {
        String server = kick.server().value();
        if (loads.groupOf(server).isEmpty()) {
            LOG.log(Level.WARNING, "Network kick from {0} ignored: the cloud did not list that server", server);
            return;
        }
        KickMarks.Outcome outcome = marks.mark(kick.uuid(), server, players.currentServer(kick.uuid()), kick.reason());
        if (outcome == KickMarks.Outcome.IGNORED) {
            LOG.log(Level.DEBUG, "Network kick from {0} ignored: the player is not on that server here", server);
            return;
        }
        if (outcome == KickMarks.Outcome.TAKEN) {
            return;
        }
        if (players.disconnect(kick.uuid(), networkKickText(kick.uuid(), kick.reason()))) {
            LOG.log(Level.INFO, "A player was kicked off the network by {0}", server);
        }
    }

    /** The reason of a network kick, or the bridge's own line when the backend gave the vanilla text. */
    private String networkKickText(UUID player, String reason) {
        return reason.isBlank() ? TransferMessages.kicked(players.locale(player)) : reason;
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
     * A kick the bridge does not catch, which the proxy turns into a redirect (Velocity's own
     * fallback through its try list). The redirect goes to the backend of the target's group with
     * the lowest load. It counts for the {@link KickGuard} like a caught kick, so redirects cannot
     * bounce a player between two servers: the servers already tried in the incident and the one
     * that kicked are skipped, and after {@link KickGuard#MAX_RESCUES} within
     * {@link KickGuard#WINDOW} the player is disconnected.
     *
     * @param player     the player
     * @param kickedFrom the backend that kicked the player
     * @param target     the server the proxy would redirect to
     * @return move to another backend (with an empty message: the proxy's own one stays); keep
     *     the proxy's target if no backend of its group is free but the target itself was not tried
     *     yet; otherwise disconnect with the original reason
     */
    public KickOutcome redirect(UUID player, String kickedFrom, String target) {
        KickOutcome giveUp = KickOutcome.disconnect(TransferMessages.lost(players.locale(player)));
        KickGuard.Attempt<KickOutcome> attempt = guard.rescue(player, kickedFrom, exclude -> {
            Optional<String> chosen = enter(player, target, exclude);
            if (chosen.isPresent()) {
                return Optional.of(KickOutcome.move(chosen.get(), ""));
            }
            return exclude.contains(target) ? Optional.empty() : Optional.of(KickOutcome.keep());
        }, outcome -> outcome.action() == KickOutcome.Action.MOVE ? outcome.server() : target);
        if (attempt.limited()) {
            LOG.log(Level.INFO, "Redirect after a kick from {0} refused: the player was moved too often in a short time",
                    kickedFrom);
        }
        return attempt.result().orElse(giveUp);
    }

    /**
     * A backend kicked the player or refused the connect. If the kick is one the proxy catches
     * ({@link KickClassifier}), the player goes to the emptiest other server of the same group,
     * otherwise to the emptiest server of the fallback group (see {@link #fallbackGroup}); servers
     * already tried in this incident are skipped ({@link KickGuard}). A player who still is on
     * another server (a refused switch) is only moved inside the group and otherwise stays. For a
     * kick with a reason of its own ({@link KickClassifier.Verdict#OTHER_REASON}) the player reads
     * that reason after the move instead of the bridge's notice. A kick the backend marked as a
     * network kick ({@code NetworkKick}, within {@link KickMarks#WINDOW}) is never caught: the
     * player is disconnected.
     *
     * @param player         the player
     * @param kickedFrom     the backend that kicked
     * @param reason         its reason
     * @param staysConnected whether the player is still on another backend
     * @return keep the proxy's result, move the player, or disconnect them with the original reason
     */
    public KickOutcome kicked(UUID player, String kickedFrom, KickReason reason, boolean staysConnected) {
        marks.kickedFrom(player, kickedFrom);
        Optional<String> marked = marks.find(player);
        if (marked.isPresent()) {
            return networkKicked(player, marked.get());
        }
        KickClassifier.Verdict verdict = KickClassifier.classify(reason);
        if (verdict == KickClassifier.Verdict.NOT_CAUGHT) {
            return KickOutcome.keep();
        }
        Locale locale = players.locale(player);
        KickOutcome giveUp = staysConnected ? KickOutcome.keep() : KickOutcome.disconnect(TransferMessages.lost(locale));
        KickGuard.Attempt<String> attempt = guard.rescue(player, kickedFrom, exclude -> {
            Optional<String> chosen = loads.groupOf(kickedFrom)
                    .flatMap(group -> loads.choose(player, group, exclude, servers::exists, servers::playersOn));
            if (chosen.isEmpty() && !staysConnected) {
                chosen = loads.choose(player, fallbackGroup, exclude, servers::exists, servers::playersOn);
            }
            return chosen;
        }, server -> server);
        if (attempt.limited()) {
            LOG.log(Level.INFO, "Kick from {0} not caught: the player was moved too often in a short time", kickedFrom);
        }
        Optional<String> target = attempt.result();
        if (target.isEmpty()) {
            return giveUp;
        }
        return verdict == KickClassifier.Verdict.OTHER_REASON
                ? KickOutcome.moveShowingReason(target.get(), TransferMessages.rescued(locale))
                : KickOutcome.move(target.get(), TransferMessages.rescued(locale));
    }

    /**
     * Like {@link #kicked}, but a kick that may be a {@code /kick} first waits up to
     * {@link KickMarks#WAIT} for the backend's network kick, which travels over the cloud and may
     * come a moment after the kick. With the mark (before, during or without the wait) the player
     * is disconnected with the backend's reason; without it the kick is handled as by
     * {@link #kicked}. Only a kick with words of its own or the vanilla kick text waits
     * ({@link KickClassifier#awaitsMark}); a stop, a crash, a full server, a ban, the whitelist, a
     * kick with the moderation marker and a refused switch of a player who stays on his server
     * are decided at once.
     *
     * @return the outcome; already complete unless the kick waits
     */
    public CompletableFuture<KickOutcome> kickedOrMarked(UUID player, String kickedFrom, KickReason reason,
                                                         boolean staysConnected) {
        marks.kickedFrom(player, kickedFrom);
        if (staysConnected || marks.find(player).isPresent() || !KickClassifier.awaitsMark(reason)) {
            return CompletableFuture.completedFuture(kicked(player, kickedFrom, reason, staysConnected));
        }
        return marks.await(player, KickMarks.WAIT).thenApply(marked -> marked
                .map(why -> networkKicked(player, why))
                .orElseGet(() -> kicked(player, kickedFrom, reason, false)));
    }

    /** A kick the backend marked: the player leaves the network. */
    private KickOutcome networkKicked(UUID player, String reason) {
        LOG.log(Level.INFO, "A kick was marked as a network kick: the player leaves the network");
        return KickOutcome.disconnect(networkKickText(player, reason));
    }

    /**
     * Whether a player may connect to a backend: not if the cloud closed it (it is being emptied
     * before it stops), unless the player has {@link #BYPASS_CLOSED_PERMISSION}. The first server
     * of a login is always allowed: Velocity leaves a player without any server hanging when that
     * connect is refused, and {@link #initialServer} already avoids closed servers where it can.
     *
     * @param server   the backend
     * @param bypass   whether the player has {@link #BYPASS_CLOSED_PERMISSION}
     * @param onServer whether the player is on a backend already (a switch, not a login)
     * @return whether the connect may go ahead
     */
    public boolean mayConnect(String server, boolean bypass, boolean onServer) {
        return bypass || !onServer || !loads.isClosed(server);
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
        guard.arrived(uuid);
        marks.arrived(uuid);
        // Queued under the tracker's lock, so events of different threads keep the tracker's order.
        // Submitting never blocks.
        synchronized (tracker) {
            tracker.connected(session, uuid, name, server).forEach(sender::send);
        }
    }

    /** The player left the proxy. {@code session} is the player object of the login that ended. */
    public void playerDisconnected(Object session, UUID uuid) {
        loads.arrived(uuid);
        guard.forget(uuid);
        marks.forget(uuid);
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
