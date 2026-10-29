package net.vectora.cloud.bridge.legacy;

import com.destroystokyo.paper.event.player.PlayerHandshakeEvent;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.vectora.cloud.bridge.LogThrottle;
import net.vectora.cloud.bridge.forwarding.AdmissionTickets;
import net.vectora.cloud.bridge.forwarding.BungeeGuardCheck;
import net.vectora.cloud.bridge.forwarding.ForwardingSecret;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

/**
 * Checks the {@code bungeeguard-token} that the legacy proxy forwards, because PandaSpigot does
 * not. All decisions come from {@link BungeeGuardCheck}; this class only maps them onto the event.
 *
 * <ul>
 *   <li>{@link PlayerHandshakeEvent} at {@code LOWEST}: check the original handshake. Accepted:
 *       hand the server the forwarded host, address, UUID and the properties without the token.
 *       Refused: fail the handshake, the client is disconnected with {@link #FAIL_MESSAGE}.</li>
 *   <li>The same at {@code MONITOR}, so that no other plugin can let a refused handshake through
 *       or hand the server the original text, token included, by cancelling the event (a
 *       cancelled event makes PandaSpigot fall back to its own unchecked BungeeCord parsing).</li>
 *   <li>{@link PlayerLoginEvent} at {@code LOWEST}: only a UUID with a ticket from an accepted
 *       handshake may log in ({@link AdmissionTickets}). A refused login is refused again at
 *       {@code MONITOR}, so no other plugin can allow it.</li>
 * </ul>
 *
 * <p>Handshake events run on network threads; the check only computes and touches no server
 * state.
 */
public final class HandshakeGuard implements Listener {

    /** What a refused client sees. Says nothing about why. */
    public static final String FAIL_MESSAGE = "Unable to verify your connection. Please join through the network address.";

    private final BungeeGuardCheck check;
    private final AdmissionTickets tickets;
    private final Logger log;
    private final LogThrottle throttle = new LogThrottle(10, 60_000_000_000L);
    // Logins refused at LOWEST, until MONITOR of the same event. By identity: one entry per event.
    private final Set<Player> refusedLogins = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    /** A guard with the given check, for tests and {@link #fromEnvironment}. */
    public HandshakeGuard(BungeeGuardCheck check, AdmissionTickets tickets, Logger log) {
        this.check = Objects.requireNonNull(check, "check");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * A guard with the secret from {@value ForwardingSecret#ENV}. Without a valid secret it
     * refuses every login and says so in the log: falling back to "accept all" would let anyone
     * who reaches the port join under any name.
     */
    public static HandshakeGuard fromEnvironment(UnaryOperator<String> environment, Logger log) {
        String value = environment.apply(ForwardingSecret.ENV);
        BungeeGuardCheck check;
        if (value == null || value.isBlank()) {
            log.severe(ForwardingSecret.ENV + " is not set; every login is refused until the cloud passes the "
                    + "forwarding secret of the legacy proxy");
            check = BungeeGuardCheck.withoutSecret();
        } else {
            try {
                check = BungeeGuardCheck.with(ForwardingSecret.of(value.strip()));
            } catch (IllegalArgumentException e) {
                // The message never contains the value.
                log.severe(e.getMessage() + "; every login is refused");
                check = BungeeGuardCheck.withoutSecret();
            }
        }
        return new HandshakeGuard(check, new AdmissionTickets(), log);
    }

    /** First look at the handshake. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onHandshake(PlayerHandshakeEvent event) {
        BungeeGuardCheck.Result result = apply(event);
        if (result instanceof BungeeGuardCheck.Accepted accepted) {
            tickets.issue(accepted.uuid());
        } else if (result instanceof BungeeGuardCheck.Rejected rejected) {
            long dropped = throttle.acquire();
            if (dropped >= 0) {
                log.log(Level.WARNING, "Refused a login from {0}: {1}{2}", new Object[] {
                    event.getOriginalSocketAddressHostname(), rejected.reason(),
                    dropped > 0 ? " (" + dropped + " more not logged)" : ""});
            }
        }
    }

    /** Applies the decision again after every other plugin. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void enforce(PlayerHandshakeEvent event) {
        apply(event);
    }

    /** Lets only players in whose handshake was accepted. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(PlayerLoginEvent event) {
        if (!tickets.consume(event.getPlayer().getUniqueId())) {
            refusedLogins.add(event.getPlayer());
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, FAIL_MESSAGE);
        }
    }

    /**
     * Refuses a login refused at {@code LOWEST} again after every other plugin, so none can let
     * it through with {@code allow()} (a full-server or whitelist plugin, for example).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void enforceLogin(PlayerLoginEvent event) {
        if (refusedLogins.remove(event.getPlayer())) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, FAIL_MESSAGE);
        }
    }

    /** Checks the event's original handshake and writes the outcome into the event. */
    BungeeGuardCheck.Result apply(PlayerHandshakeEvent event) {
        BungeeGuardCheck.Result result = check.check(event.getOriginalHandshake());
        // Not cancelled: PandaSpigot then takes the values below instead of parsing the handshake.
        event.setCancelled(false);
        if (result instanceof BungeeGuardCheck.Accepted accepted) {
            event.setFailed(false);
            event.setServerHostname(accepted.serverHostname());
            event.setSocketAddressHostname(accepted.playerAddress());
            event.setUniqueId(accepted.uuid());
            event.setPropertiesJson(accepted.propertiesJson());
        } else {
            event.setFailed(true);
            event.setFailMessage(FAIL_MESSAGE);
        }
        return result;
    }
}
