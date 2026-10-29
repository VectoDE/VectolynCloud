package net.vectora.cloud.bridge.paper;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.vectora.cloud.bridge.BridgeConnection;
import net.vectora.cloud.bridge.BridgeSender;
import net.vectora.cloud.bridge.KickClassifier;
import net.vectora.cloud.bridge.KickCommandWatch;
import net.vectora.cloud.bridge.StatusSnapshot;
import net.vectora.cloud.client.CloudClientSettings;
import net.vectora.protocol.ServiceId;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
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
 * VectoraCloud bridge for Paper 26.3. Thin adapter over cloud-bridge-common: every call to the
 * cloud goes through {@link BridgeSender}, the main thread only takes a snapshot and queues it.
 *
 * <ul>
 *   <li>Ready: the first server tick, which comes after "Done".</li>
 *   <li>Status: every {@value #STATUS_PERIOD_TICKS} ticks with Paper's TPS and MSPT, and at once
 *       when a player joins or leaves.</li>
 *   <li>Network kicks: a {@code /kick} of the console, of RCON or of a player with
 *       {@value KickCommandWatch#PERMISSION} ({@link KickCommandWatch}, aliases of
 *       {@code commands.yml} and selectors included) followed by the kick of a target in the same
 *       tick is sent to the proxy as {@code NetworkKick}, which takes the player off the network.
 *       Command blocks are no source. The moderation uses {@link #networkKick}.</li>
 * </ul>
 *
 * <p>Network-wide player events ({@code PlayerJoin} and so on) are reported by the proxy only, so
 * the cloud gets each of them once.
 */
public final class PaperBridgePlugin extends JavaPlugin implements Listener {

    /** Status period: five seconds at full speed. */
    public static final long STATUS_PERIOD_TICKS = 100;

    private final KickCommandWatch kicks = new KickCommandWatch();
    private BridgeSender sender;
    private ServiceId service;

    @Override
    public void onEnable() {
        Optional<CloudClientSettings> settings = BridgeConnection.settings();
        if (settings.isEmpty()) {
            getLogger().info("Not started by VectoraCloud (no connection settings): the bridge stays idle");
            return;
        }
        getLogger().info("VectoraCloud bridge runs as a backend behind the main proxy (modern forwarding)");
        service = settings.get().service();
        sender = BridgeConnection.open(settings.get(), created -> null);
        // The player count changes: report at once. The leaving player is still counted during the
        // quit event. Registered as executors, the handlers need nothing from the event.
        getServer().getPluginManager().registerEvent(PlayerJoinEvent.class, this, EventPriority.MONITOR,
                (listener, event) -> report(0), this, false);
        getServer().getPluginManager().registerEvent(PlayerQuitEvent.class, this, EventPriority.MONITOR,
                (listener, event) -> report(-1), this, false);
        // Network kicks: the command line first, then the kick it causes in the same tick.
        getServer().getPluginManager().registerEvent(PlayerCommandPreprocessEvent.class, this, EventPriority.MONITOR,
                (listener, event) -> {
                    if (event instanceof PlayerCommandPreprocessEvent command) {
                        command(command.getPlayer(), command.getMessage());
                    }
                }, this, true);
        getServer().getPluginManager().registerEvent(ServerCommandEvent.class, this, EventPriority.MONITOR,
                (listener, event) -> {
                    if (event instanceof ServerCommandEvent command) {
                        command(command.getSender(), command.getCommand());
                    }
                }, this, true);
        // RCON has a handler list of its own.
        getServer().getPluginManager().registerEvent(RemoteServerCommandEvent.class, this, EventPriority.MONITOR,
                (listener, event) -> {
                    if (event instanceof RemoteServerCommandEvent command) {
                        command(command.getSender(), command.getCommand());
                    }
                }, this, true);
        getServer().getPluginManager().registerEvent(PlayerKickEvent.class, this, EventPriority.MONITOR,
                (listener, event) -> {
                    if (event instanceof PlayerKickEvent kick) {
                        kicked(kick);
                    }
                }, this, true);
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

    /**
     * Takes a player off the whole network: tells the proxy ({@code NetworkKick}) and kicks the
     * player here with the moderation marker ({@link KickClassifier#MODERATION_KEY}), so the
     * proxy never catches the kick even if the report is late. For the moderation of Vectora; only
     * an entitled issuer may do it ({@link KickCommandWatch#entitled}).
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
        player.kick(Component.translatable().key(KickClassifier.MODERATION_KEY + ".kick").fallback("%s")
                .arguments(Component.text(reason)).build(), PlayerKickEvent.Cause.PLUGIN);
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

    /**
     * The plain text of a kick reason; empty for the vanilla {@code /kick} without a reason, so
     * the proxy shows its own line in the player's language.
     *
     * @param reason the reason component
     * @return plain text
     */
    static String plain(Component reason) {
        if (reason instanceof TranslatableComponent translatable
                && translatable.key().startsWith(KickClassifier.VANILLA_KICK_KEY) && reason.children().isEmpty()) {
            return "";
        }
        return PlainTextComponentSerializer.plainText().serialize(reason);
    }

    private void command(CommandSender issuer, String line) {
        if (sender == null) {
            return;
        }
        if (kicks.command(line, source(issuer), entitledPlayer(issuer), aliases())) {
            getServer().getScheduler().runTask(this, kicks::clear);
        }
    }

    private static boolean entitledPlayer(CommandSender issuer) {
        return issuer instanceof Player && issuer.hasPermission(KickCommandWatch.PERMISSION);
    }

    /**
     * The kick of a target of a watched {@code /kick}. The kick cause is not used: Paper marks
     * {@code KICK_COMMAND} deprecated since 26.2, and a {@code /kick} that a plugin such as
     * Essentials provides kicks with the cause {@code PLUGIN}. The command line and its target in
     * the same tick decide, as on 1.8.8.
     */
    private void kicked(PlayerKickEvent event) {
        if (kicks.kicked(event.getPlayer().getName())) {
            report(event.getPlayer(), plain(event.reason()));
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
        target.reportStatus(new StatusSnapshot(online, Bukkit.getMaxPlayers(), Bukkit.getTPS()[0],
                Bukkit.getAverageTickTime()));
    }
}
