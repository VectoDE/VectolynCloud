package net.vectora.cloud.bridge.legacy;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.vectora.cloud.bridge.BridgeConnection;
import net.vectora.cloud.bridge.BridgeSender;
import net.vectora.cloud.bridge.KickCommandWatch;
import net.vectora.cloud.bridge.StatusSnapshot;
import net.vectora.cloud.bridge.TickMeter;
import net.vectora.cloud.client.CloudClientSettings;
import net.vectora.protocol.ServiceId;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VectoraCloud bridge for PandaSpigot 1.8.8. Thin adapter: the token check lives in
 * {@link HandshakeGuard} and {@code BungeeGuardCheck}, the cloud calls go through
 * {@link BridgeSender}, never from the server thread.
 *
 * <ul>
 *   <li>The token check is enabled first and independently of the cloud connection.</li>
 *   <li>Ready: the first server tick, which comes after "Done".</li>
 *   <li>Status: every {@value #STATUS_PERIOD_TICKS} ticks, and at once when a player joins or
 *       leaves. TPS and MSPT come from {@code ServerTickEndEvent}, as 1.8.8 has no TPS API.</li>
 *   <li>Network kicks: 1.8.8 has no kick cause, so the bridge reads the command line. A
 *       {@code /kick <player>} of the console, of RCON or of a player with
 *       {@value KickCommandWatch#PERMISSION} ({@link KickCommandWatch}, aliases of
 *       {@code commands.yml} included) followed by the kick of that player in the same tick is
 *       sent to the proxy as {@code NetworkKick}, which takes the player off the network. Command
 *       blocks are no source. The moderation uses {@link #networkKick}.</li>
 * </ul>
 *
 * <p>Network-wide player events ({@code PlayerJoin} and so on) are reported by the proxy only, so
 * the cloud gets each of them once.
 */
public final class LegacyBridgePlugin extends JavaPlugin implements Listener {

    /** Status period: five seconds at full speed. */
    public static final long STATUS_PERIOD_TICKS = 100;

    private final TickMeter ticks = new TickMeter();
    private final KickCommandWatch kicks = new KickCommandWatch();
    private BridgeSender sender;
    private ServiceId service;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(HandshakeGuard.fromEnvironment(System::getenv, getLogger()), this);
        Optional<CloudClientSettings> settings = BridgeConnection.settings();
        if (!settings.isPresent()) {
            getLogger().info("Not started by VectoraCloud (no connection settings): only the BungeeGuard check runs");
            return;
        }
        getLogger().info("VectoraCloud bridge runs as a 1.8.8 backend behind the legacy proxy (BungeeGuard check)");
        service = settings.get().service();
        sender = BridgeConnection.open(settings.get(), created -> null);
        getServer().getPluginManager().registerEvents(this, this);
        // The player count changes: report at once. The leaving player is still counted during the
        // quit event. Registered as executors, the handlers need nothing from the event.
        getServer().getPluginManager().registerEvent(PlayerJoinEvent.class, this, EventPriority.MONITOR,
                (listener, event) -> report(0), this, false);
        getServer().getPluginManager().registerEvent(PlayerQuitEvent.class, this, EventPriority.MONITOR,
                (listener, event) -> report(-1), this, false);
        BridgeSender ready = sender;
        getServer().getScheduler().runTask(this, ready::markReady);
        getServer().getScheduler().runTaskTimer(this, () -> report(0), STATUS_PERIOD_TICKS, STATUS_PERIOD_TICKS);
    }

    @Override
    public void onDisable() {
        if (sender != null) {
            sender.close();
            sender = null;
        }
    }

    /** Feeds the tick meter. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        ticks.tick(System.nanoTime(), event.getTickDuration());
    }

    /** A player's command line; a {@code /kick} of an entitled player is watched for this tick. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        command(event.getPlayer(), event.getMessage());
    }

    /** A command line of the console (command blocks are no source, see {@link #source}). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        command(event.getSender(), event.getCommand());
    }

    /** A command line of RCON, which has a handler list of its own. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRemoteCommand(RemoteServerCommandEvent event) {
        command(event.getSender(), event.getCommand());
    }

    /** The kick a watched {@code /kick} causes goes to the proxy as a network kick. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        if (kicks.kicked(event.getPlayer().getName())) {
            report(event.getPlayer(), event.getReason());
        }
    }

    /**
     * Takes a player off the whole network: tells the proxy ({@code NetworkKick}) and kicks the
     * player here. For the moderation of Vectora; only an entitled issuer may do it
     * ({@link KickCommandWatch#entitled}). On 1.8.8 the kick is plain text and cannot carry the
     * moderation marker, so the proxy relies on the report alone.
     *
     * @param issuer who kicks: the console, RCON or a player with {@value KickCommandWatch#PERMISSION}
     * @param player the player
     * @param reason the plain text the player reads
     * @return {@code false} if the issuer is not entitled (nothing happens); {@code true} otherwise,
     *     the kick happened and the report is queued if the bridge is connected
     */
    public boolean networkKick(CommandSender issuer, Player player, String reason) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(reason, "reason");
        if (!entitled(issuer)) {
            return false;
        }
        report(player, reason);
        player.kickPlayer(reason);
        return true;
    }

    /**
     * Who issued a command, for {@link KickCommandWatch}.
     *
     * @param sender the sender of the command
     * @return the console, RCON, a player, or {@code OTHER} for command blocks and the rest
     */
    static KickCommandWatch.Source source(CommandSender sender) {
        if (sender instanceof RemoteConsoleCommandSender) {
            return KickCommandWatch.Source.REMOTE_CONSOLE;
        }
        if (sender instanceof ConsoleCommandSender) {
            return KickCommandWatch.Source.CONSOLE;
        }
        if (sender instanceof Player) {
            return KickCommandWatch.Source.PLAYER;
        }
        return KickCommandWatch.Source.OTHER;
    }

    /**
     * Whether a sender may take a player off the network.
     *
     * @param sender the sender
     * @return see {@link KickCommandWatch#entitled}
     */
    static boolean entitled(CommandSender sender) {
        KickCommandWatch.Source source = source(sender);
        return KickCommandWatch.entitled(source,
                source == KickCommandWatch.Source.PLAYER && sender.hasPermission(KickCommandWatch.PERMISSION));
    }

    private void command(CommandSender issuer, String line) {
        if (sender == null) {
            return;
        }
        boolean permitted = issuer instanceof Player && issuer.hasPermission(KickCommandWatch.PERMISSION);
        if (kicks.command(line, source(issuer), permitted, aliases())) {
            getServer().getScheduler().runTask(this, kicks::clear);
        }
    }

    private void report(Player player, String reason) {
        BridgeSender target = sender;
        if (target != null && service != null) {
            target.send(KickCommandWatch.message(player.getUniqueId(), service, reason));
        }
    }

    private Map<String, List<String>> aliases() {
        Map<String, List<String>> result = new HashMap<>();
        for (Map.Entry<String, String[]> entry : getServer().getCommandAliases().entrySet()) {
            result.put(entry.getKey().toLowerCase(Locale.ROOT), Arrays.asList(entry.getValue()));
        }
        return result;
    }

    private void report(int adjust) {
        BridgeSender target = sender;
        if (target == null) {
            return;
        }
        int online = Math.max(0, Bukkit.getOnlinePlayers().size() + adjust);
        target.reportStatus(new StatusSnapshot(online, Bukkit.getMaxPlayers(), ticks.tps(), ticks.mspt()));
    }
}
