package net.vectora.cloud.bridge.legacy;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.vectora.cloud.bridge.BridgeConnection;
import net.vectora.cloud.bridge.BridgeSender;
import net.vectora.cloud.bridge.KickCommandWatch;
import net.vectora.cloud.bridge.OwnKick;
import net.vectora.cloud.bridge.StatusSnapshot;
import net.vectora.cloud.bridge.TickMeter;
import net.vectora.cloud.client.CloudClientSettings;
import net.vectora.protocol.ServiceId;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.command.SimpleCommandMap;
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
 *   <li>Network kicks: at the first tick, after the server registered its vanilla commands, the
 *       bridge puts {@link NetworkKickCommand} in place of {@code kick} and {@code minecraft:kick}
 *       in the command map. It kicks through {@link #networkKick}, which tells the proxy
 *       ({@code NetworkKick}) to take the player off the network; only the console, RCON and a
 *       player with {@value KickCommandWatch#PERMISSION} can use it, command blocks are no source.
 *       The command line watch ({@link KickCommandWatch}) stays for selectors, which the vanilla
 *       command resolves, for the {@code kick} commands of other plugins and as a fallback.</li>
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
    /** The server's kick command, for selectors; {@code null} until replaced. */
    private Command vanillaKick;
    /** The kick {@link #networkKick} carries out right now; reported once its event went through. */
    private final OwnKick own = new OwnKick();

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
        // The vanilla commands are registered after the plugins are enabled: replace kick at the first tick.
        getServer().getScheduler().runTask(this, this::replaceKick);
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

    /**
     * A kick that went through (at {@code MONITOR}, cancelled ones skipped). The kick of
     * {@link #networkKick} is reported now, after the event's outcome; otherwise the kick of a
     * target of a watched {@code /kick} goes to the proxy as a network kick. The reason is read
     * through PandaSpigot's Adventure API ({@code reason()}), not the old string getter.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        Player player = event.getPlayer();
        Optional<String> ownReason = own.passed(player.getUniqueId());
        if (ownReason.isPresent()) {
            report(player, ownReason.get());
            return;
        }
        String reason = plain(event.reason());
        if (!own.running(player.getUniqueId()) && kicks.kicked(player.getName(), reason)) {
            report(player, reason);
        }
    }

    /**
     * Takes a player off the whole network: kicks the player here and tells the proxy
     * ({@code NetworkKick}) once the kick event went through ({@link OwnKick}): a kick another
     * plugin cancels is not reported. For the moderation of Vectora; only an entitled issuer may
     * do it ({@link KickCommandWatch#entitled}). The 1.8.8 client shows only plain text, so the
     * kick cannot carry the moderation marker and the proxy relies on the report.
     *
     * @param issuer who kicks: the console, RCON or a player with {@value KickCommandWatch#PERMISSION}
     * @param player the player
     * @param reason the plain text the player reads
     * @return {@code false} if the issuer is not entitled or a plugin cancelled the kick (nothing
     *     happens); {@code true} if the kick happened and its report is queued. Without a cloud
     *     connection the bridge has no kick listener: then it only kicks and returns {@code true}.
     */
    public boolean networkKick(CommandSender issuer, Player player, String reason) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(reason, "reason");
        if (!entitled(issuer)) {
            return false;
        }
        String text = reason.trim();
        // The vanilla text without a reason: the proxy waits for the report on it.
        Runnable kick = () -> player.kick(Component.text(text.isEmpty() ? NetworkKickCommand.VANILLA_KICK_TEXT : text));
        if (sender == null) {
            kick.run();
            return true;
        }
        return own.run(player.getUniqueId(), text, kick);
    }

    /**
     * The plain text of a kick reason, colour codes of legacy text removed later by
     * {@link KickCommandWatch#reason}.
     *
     * @param reason the reason component
     * @return plain text
     */
    static String plain(Component reason) {
        StringBuilder out = new StringBuilder();
        ComponentFlattener.basic().flatten(reason, out::append);
        return out.toString();
    }

    /** Puts {@link NetworkKickCommand} in place of {@code kick} and {@code minecraft:kick}. */
    private void replaceKick() {
        try {
            CommandMap map = getServer().getCommandMap();
            if (!(map instanceof SimpleCommandMap)) {
                throw new IllegalStateException("Unknown command map " + map.getClass().getName());
            }
            SimpleCommandMap simple = (SimpleCommandMap) map;
            Map<String, Command> known = NetworkKickCommand.knownCommands(simple);
            NetworkKickCommand command = new NetworkKickCommand(getServer()::getPlayerExact, this::networkKick,
                    this::selectorKick);
            // Gives the command its timings, which the map's dispatch needs, and vectoracloud:kick.
            simple.register("vectoracloud", command);
            vanillaKick = NetworkKickCommand.install(known, command);
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "The kick command could not be replaced: only the command line "
                    + "watch turns a /kick into a network kick", e);
        }
    }

    /** A selector goes to the server's kick command; the kicks it causes in this tick are network kicks. */
    private boolean selectorKick(CommandSender issuer, String label, String[] args) {
        Command vanilla = vanillaKick;
        if (vanilla == null) {
            issuer.sendMessage(ChatColor.RED + "Selectors are not available for /kick on this server");
            return true;
        }
        // Vanilla 1.8.8 kicks with the rest of the line, or its own text without one.
        String reason = KickCommandWatch.reasonOf(args);
        kicks.expect(args[0], reason.isEmpty() ? NetworkKickCommand.VANILLA_KICK_TEXT : reason,
                KickCommandWatch.Selectors.NONE);
        getServer().getScheduler().runTask(this, kicks::clear);
        return vanilla.execute(issuer, label, args);
    }

    /**
     * Who issued a command, for {@link KickCommandWatch}. A proxied sender ({@code /execute})
     * counts as its caller.
     *
     * @param sender the sender of the command
     * @return the console, RCON, a player, or {@code OTHER} for command blocks and the rest
     */
    static KickCommandWatch.Source source(CommandSender sender) {
        if (sender instanceof ProxiedCommandSender) {
            return source(((ProxiedCommandSender) sender).getCaller());
        }
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
        if (sender instanceof ProxiedCommandSender) {
            return entitled(((ProxiedCommandSender) sender).getCaller());
        }
        KickCommandWatch.Source source = source(sender);
        return KickCommandWatch.entitled(source,
                source == KickCommandWatch.Source.PLAYER && sender.hasPermission(KickCommandWatch.PERMISSION));
    }

    private void command(CommandSender issuer, String line) {
        if (sender == null) {
            return;
        }
        KickCommandWatch.Source source = source(issuer);
        // 1.8.8 has no selector API: an unresolved selector only matches kicks with the line's reason.
        if (kicks.command(line, source, source == KickCommandWatch.Source.PLAYER && entitled(issuer), aliases(),
                KickCommandWatch.Selectors.NONE)) {
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
