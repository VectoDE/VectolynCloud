package net.vectora.cloud.bridge.paper;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.vectora.cloud.bridge.BridgeConnection;
import net.vectora.cloud.bridge.BridgeSender;
import net.vectora.cloud.bridge.KickClassifier;
import net.vectora.cloud.bridge.KickCommandWatch;
import net.vectora.cloud.bridge.OwnKick;
import net.vectora.cloud.bridge.StatusSnapshot;
import net.vectora.cloud.client.CloudClientSettings;
import net.vectora.protocol.ServiceId;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.ProxiedCommandSender;
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
 *   <li>Network kicks: the bridge puts its own command in place of {@code kick} and
 *       {@code minecraft:kick} (Brigadier, {@link KickCommandNode}). It has the vanilla shape,
 *       {@code kick <targets> [<reason>]} with selectors, and kicks through {@link #networkKick},
 *       which tells the proxy ({@code NetworkKick}) to take the player off the network. Only the
 *       console, RCON and a player with {@value KickCommandWatch#PERMISSION} (operators by
 *       default) can use it; command blocks are no source. As the command sits in the dispatcher's
 *       root, {@code execute ... run kick}, {@code dispatchCommand}, {@code performCommand} and
 *       aliases of {@code commands.yml} run it too. The command line watch ({@link KickCommandWatch})
 *       stays for the {@code kick} commands of other plugins and as a fallback.</li>
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
    /** The kick {@link #networkKick} carries out right now; reported once its event went through. */
    private final OwnKick own = new OwnKick();

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
        // Network kicks: the bridge's own kick command, also after every reload of the dispatcher.
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> registerKick(event.registrar()));
        // Second way for the kick commands of other plugins: the command line first, then the kick
        // it causes in the same tick.
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
     * Takes a player off the whole network: kicks the player here with the moderation marker
     * ({@link KickClassifier#MODERATION_KEY}), so the proxy never catches the kick even if the
     * report is late, and tells the proxy ({@code NetworkKick}) once the kick event went through
     * ({@link OwnKick}): a kick another plugin cancels is not reported. For the moderation of
     * Vectora; only an entitled issuer may do it ({@link KickCommandWatch#entitled}).
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
        String text = reason.strip();
        Component shown = text.isEmpty() ? Component.translatable(KickClassifier.VANILLA_KICK_KEY) : Component.text(text);
        Runnable kick = () -> player.kick(Component.translatable().key(KickClassifier.MODERATION_KEY + ".kick")
                .fallback("%s").arguments(shown).build(), PlayerKickEvent.Cause.PLUGIN);
        if (sender == null) {
            kick.run();
            return true;
        }
        return own.run(player.getUniqueId(), text, kick);
    }

    /**
     * Puts the bridge's command in place of {@code kick} and {@code minecraft:kick}. Paper lets a
     * plugin command take a vanilla name, so {@code kick} is registered the regular way;
     * {@code minecraft:kick} is no name a plugin can register and is replaced in the dispatcher.
     * If that fails, the vanilla command stays and the command line watch is the only way left.
     */
    private void registerKick(Commands registrar) {
        Predicate<CommandSourceStack> requirement = Commands.restricted(source -> entitled(source.getSender()));
        Set<String> labels = registrar.register(kickNode("kick", requirement),
                "Kicks players off the whole network (VectoraCloud)");
        CommandNode<CommandSourceStack> root = registrar.getDispatcher().getRoot();
        // Any failure of the server's Brigadier: warn and fall back to the command line watch.
        KickCommandNode.install(root, labels.contains("kick"), literal -> kickNode(literal, requirement),
                e -> getLogger().log(Level.WARNING, "The kick command could not be replaced everywhere: only the "
                        + "command line watch turns a /kick into a network kick", e));
    }

    private LiteralCommandNode<CommandSourceStack> kickNode(String literal, Predicate<CommandSourceStack> requirement) {
        return KickCommandNode.build(literal, requirement, ArgumentTypes.players(), this::kick);
    }

    /** The bridge's {@code kick}: every target leaves the network, like the vanilla command reports it. */
    private int kick(CommandContext<CommandSourceStack> context, String reason) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        List<Player> targets = context.getArgument(KickCommandNode.TARGETS, PlayerSelectorArgumentResolver.class)
                .resolve(source);
        Component shown = reason.isEmpty() ? Component.translatable(KickClassifier.VANILLA_KICK_KEY) : Component.text(reason);
        int kicked = 0;
        for (Player target : targets) {
            if (networkKick(source.getSender(), target, reason)) {
                kicked++;
                source.sendSuccess(Component.translatable("commands.kick.success", target.displayName(), shown), true);
            }
        }
        return kicked;
    }

    /**
     * Who issued a command, for {@link KickCommandWatch}. A proxied sender counts as its caller.
     *
     * @param sender the sender of the command
     * @return the console, RCON, a player, or {@code OTHER} for command blocks and the rest
     */
    static KickCommandWatch.Source source(CommandSender sender) {
        if (sender instanceof ProxiedCommandSender proxied) {
            return source(proxied.getCaller());
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
        if (sender instanceof ProxiedCommandSender proxied) {
            return entitled(proxied.getCaller());
        }
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
        KickCommandWatch.Source source = source(issuer);
        if (kicks.command(line, source, source == KickCommandWatch.Source.PLAYER && entitled(issuer), aliases(),
                selector -> select(issuer, selector))) {
            getServer().getScheduler().runTask(this, kicks::clear);
        }
    }

    /** The players a selector matches for the sender; empty if Paper rejects it. */
    private Optional<List<String>> select(CommandSender issuer, String selector) {
        try {
            return Optional.of(getServer().selectEntities(issuer, selector).stream()
                    .filter(Player.class::isInstance)
                    .map(entity -> ((Player) entity).getName())
                    .toList());
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    /**
     * A kick event that went through: registered at {@code MONITOR} for events nobody cancelled.
     * The kick of {@link #networkKick} is reported now, after the event's outcome. Otherwise the
     * kick of a target of a watched {@code /kick} of another plugin is reported. The kick cause is
     * not used: Paper marks {@code KICK_COMMAND} deprecated since 26.2, and a {@code /kick} that a
     * plugin such as Essentials provides kicks with the cause {@code PLUGIN}.
     */
    private void kicked(PlayerKickEvent event) {
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
