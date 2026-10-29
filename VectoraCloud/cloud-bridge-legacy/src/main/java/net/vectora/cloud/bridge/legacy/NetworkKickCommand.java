package net.vectora.cloud.bridge.legacy;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import net.vectora.cloud.bridge.KickCommandWatch;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.entity.Player;

/**
 * The bridge's {@code kick} on 1.8.8, in the command map in place of {@code kick} and
 * {@code minecraft:kick} ({@link #install}). Everything that runs a command through the map runs
 * it: the chat, the console, RCON, {@code dispatchCommand}, {@code performCommand}, aliases of
 * {@code commands.yml} and {@code /execute}, which CraftBukkit 1.8.8 dispatches through the map
 * with a proxied sender.
 *
 * <ul>
 *   <li>Only an entitled sender may use it ({@link LegacyBridgePlugin#entitled}: the console, RCON,
 *       a player with {@value KickCommandWatch#PERMISSION}; a proxied sender counts as its caller).
 *       Everybody else gets the vanilla permission message.</li>
 *   <li>{@code kick <player> [reason ...]}: the player leaves the network through
 *       {@code networkKick}.</li>
 *   <li>{@code kick @selector [reason ...]}: the 1.8.8 API cannot resolve selectors, so the line
 *       goes to the vanilla command, and the kicks it causes in this tick are reported as network
 *       kicks ({@link KickCommandWatch#expect}). The vanilla command checks its own permission
 *       ({@code minecraft.command.kick}, operators) for that.</li>
 * </ul>
 */
final class NetworkKickCommand extends Command {

    /** The text of a vanilla 1.8.8 kick without a reason. */
    static final String VANILLA_KICK_TEXT = "Kicked by an operator.";

    /** Kicks a player off the network. */
    @FunctionalInterface
    interface Kicker {
        /**
         * Kicks a player off the network.
         *
         * @param issuer who kicks
         * @param player the player
         * @param reason the reason, empty without one
         * @return whether the player was kicked
         */
        boolean networkKick(CommandSender issuer, Player player, String reason);
    }

    /** Hands a selector on to the server, expecting the kicks it causes. */
    @FunctionalInterface
    interface SelectorKick {
        /**
         * Runs the line with the server's own kick command.
         *
         * @param issuer who kicks
         * @param label  the label used
         * @param args   the arguments, the selector first
         * @return the result of the server's command
         */
        boolean kick(CommandSender issuer, String label, String[] args);
    }

    private final Function<String, Player> players;
    private final Kicker kicker;
    private final SelectorKick selectors;

    /**
     * @param players   finds an online player by name, ignoring case, or {@code null}
     * @param kicker    the network kick
     * @param selectors the hand-over of a selector line, see the class comment
     */
    NetworkKickCommand(Function<String, Player> players, Kicker kicker, SelectorKick selectors) {
        super("kick", "Kicks players off the whole network (VectoraCloud)", "/kick <player> [reason ...]",
                Collections.<String>emptyList());
        this.players = Objects.requireNonNull(players, "players");
        this.kicker = Objects.requireNonNull(kicker, "kicker");
        this.selectors = Objects.requireNonNull(selectors, "selectors");
        setPermission(KickCommandWatch.PERMISSION);
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!LegacyBridgePlugin.entitled(sender)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }
        if (args.length == 0 || args[0].isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Usage: " + getUsage());
            return true;
        }
        if (args[0].startsWith("@")) {
            return selectors.kick(sender, label, args);
        }
        Player target = players.apply(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "That player cannot be found");
            return true;
        }
        String reason = reason(args);
        if (kicker.networkKick(sender, target, reason)) {
            sender.sendMessage(reason.isEmpty()
                    ? "Kicked " + target.getName() + " from the game"
                    : "Kicked " + target.getName() + " from the game: '" + reason + "'");
        }
        return true;
    }

    /**
     * The reason of a kick line: everything after the target, as vanilla joins it.
     *
     * @param args the arguments after the label
     * @return the reason, empty without one
     */
    static String reason(String[] args) {
        return args.length < 2 ? "" : String.join(" ", Arrays.asList(args).subList(1, args.length)).trim();
    }

    /**
     * Puts the command in place of {@code kick} and {@code minecraft:kick}. Call it after the server
     * registered its vanilla commands (the first tick); the command must already carry its timings,
     * which {@link SimpleCommandMap#register(String, Command)} gives it.
     *
     * @param known   the command map's labels
     * @param command the bridge's command
     * @return the vanilla command for selectors: {@code minecraft:kick} as it was, else {@code kick},
     *     {@code null} if there was none
     */
    static Command install(Map<String, Command> known, NetworkKickCommand command) {
        Command vanilla = known.get("minecraft:kick");
        if (vanilla == null || vanilla instanceof NetworkKickCommand) {
            vanilla = known.get("kick");
        }
        if (vanilla instanceof NetworkKickCommand) {
            vanilla = null;
        }
        known.put("kick", command);
        known.put("minecraft:kick", command);
        return vanilla;
    }

    /**
     * The labels of a command map. 1.8.8 has no getter for them.
     *
     * @param map the command map
     * @return its live map of labels
     * @throws IllegalStateException if the field cannot be read
     */
    @SuppressWarnings("unchecked")
    static Map<String, Command> knownCommands(SimpleCommandMap map) {
        try {
            Field field = SimpleCommandMap.class.getDeclaredField("knownCommands");
            field.setAccessible(true);
            return (Map<String, Command>) field.get(map);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Cannot read the commands of the command map", e);
        }
    }
}
