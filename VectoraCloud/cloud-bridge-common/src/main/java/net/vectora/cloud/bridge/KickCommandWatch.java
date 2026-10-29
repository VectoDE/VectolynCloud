package net.vectora.cloud.bridge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.NetworkKick;

/**
 * Backend side of network kicks, without a platform type: recognises a {@code /kick} of an
 * entitled sender, so that the kick that follows in the same server tick is reported to the proxy
 * as a {@link NetworkKick} and takes the player off the whole network.
 *
 * <ol>
 *   <li>The platform passes every command line of the console, of RCON and of a player
 *       ({@link #command}). Command blocks and command minecarts are no source
 *       ({@link Source#OTHER}).</li>
 *   <li>The line counts if the sender is entitled ({@link #entitled}: console, RCON, or a player
 *       with {@value #PERMISSION}, which the plugin gives to operators by default) and the command
 *       is {@code kick}, {@code minecraft:kick} or any {@code <plugin>:kick}, also behind an alias
 *       of {@code commands.yml} ({@link #targets}).</li>
 *   <li>A kick of a target of that line in the same tick ({@link #kicked}) is a network kick. The
 *       platform clears the watch at the next tick ({@link #clear}).</li>
 * </ol>
 * Thread-safe; the platforms call it from the server thread.
 */
public final class KickCommandWatch {

    /** Lets a player's {@code /kick} take the target off the whole network. */
    public static final String PERMISSION = "vectora.moderation.kick";

    /** Who issued a command. */
    public enum Source {
        /** The server console. */
        CONSOLE,
        /** RCON. */
        REMOTE_CONSOLE,
        /** A player. */
        PLAYER,
        /** Anything else: command blocks, command minecarts, proxied senders. Never entitled. */
        OTHER
    }

    private static final Pattern COLOUR_CODES = Pattern.compile("§.");
    private static final Pattern ALIAS_ARGUMENT = Pattern.compile("\\$\\$?(\\d+)(-?)");
    private static final int MAX_ALIAS_DEPTH = 3;

    private final Set<String> names = new HashSet<>();
    private boolean anyTarget;

    /**
     * Whether a sender may take a player off the network with {@code /kick} or the moderation's
     * {@code networkKick}.
     *
     * @param source        who issued it
     * @param hasPermission for a player: whether they have {@value #PERMISSION}
     * @return {@code true} for the console and RCON, for a player only with the permission, never
     *     for anything else
     */
    public static boolean entitled(Source source, boolean hasPermission) {
        return switch (Objects.requireNonNull(source, "source")) {
            case CONSOLE, REMOTE_CONSOLE -> true;
            case PLAYER -> hasPermission;
            case OTHER -> false;
        };
    }

    /**
     * A command line was issued. Starts a new watch: whatever the previous line expected is gone.
     *
     * @param line          the line, with or without the leading slash
     * @param source        who issued it
     * @param hasPermission for a player: whether they have {@value #PERMISSION}
     * @param aliases       the aliases of {@code commands.yml}: lower-case name to command lines
     * @return whether a kick is now expected; the platform then clears the watch at the next tick
     */
    public synchronized boolean command(String line, Source source, boolean hasPermission,
                                        Map<String, List<String>> aliases) {
        clear();
        if (!entitled(source, hasPermission)) {
            return false;
        }
        Optional<List<String>> found = targets(line, aliases);
        if (found.isEmpty()) {
            return false;
        }
        for (String target : found.get()) {
            if (target.startsWith("@")) {
                anyTarget = true;
            } else {
                names.add(target.toLowerCase(Locale.ROOT));
            }
        }
        return true;
    }

    /**
     * A player is being kicked.
     *
     * @param playerName the player's name
     * @return whether the kick comes from the {@code /kick} of an entitled sender in this tick: a
     *     named target, or anyone for a selector ({@code @a}, {@code @p}, ...)
     */
    public synchronized boolean kicked(String playerName) {
        return anyTarget || names.contains(playerName.toLowerCase(Locale.ROOT));
    }

    /** Forgets the current line; at the next tick and before each new line. */
    public synchronized void clear() {
        names.clear();
        anyTarget = false;
    }

    /**
     * The targets of a kick command.
     *
     * @param line    the command line, with or without the leading slash
     * @param aliases the aliases of {@code commands.yml}: lower-case name to command lines
     *                ({@code $1}, {@code $$1} and {@code $1-} stand for the alias's arguments)
     * @return the first argument of each kick command the line runs (a name or a selector),
     *     empty if it runs none
     */
    public static Optional<List<String>> targets(String line, Map<String, List<String>> aliases) {
        Objects.requireNonNull(aliases, "aliases");
        List<String> found = new ArrayList<>();
        collect(Objects.requireNonNull(line, "line"), aliases, 0, found);
        return found.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(found));
    }

    private static void collect(String line, Map<String, List<String>> aliases, int depth, List<String> found) {
        String trimmed = line.strip();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return;
        }
        String label = parts[0].toLowerCase(Locale.ROOT);
        if (label.equals("kick") || label.endsWith(":kick")) {
            if (parts.length > 1) {
                found.add(parts[1]);
            }
            return;
        }
        List<String> expansions = aliases.get(label);
        if (expansions == null || depth >= MAX_ALIAS_DEPTH) {
            return;
        }
        for (String expansion : expansions) {
            collect(expand(expansion, parts), aliases, depth + 1, found);
        }
    }

    /** Puts the alias's arguments into one of its command lines, like Bukkit's alias does. */
    private static String expand(String expansion, String[] parts) {
        Matcher matcher = ALIAS_ARGUMENT.matcher(expansion);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            String value;
            if (index < 1 || index >= parts.length) {
                value = "";
            } else if (matcher.group(2).isEmpty()) {
                value = parts[index];
            } else {
                value = String.join(" ", List.of(parts).subList(index, parts.length));
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * The reason a backend reports: without colour codes, trimmed, at most
     * {@value NetworkKick#MAX_REASON_LENGTH} characters. The vanilla text of 1.8.8
     * ({@code Kicked by an operator.}) becomes empty, so the proxy shows its own line in the
     * player's language.
     *
     * @param text the kick reason as plain text
     * @return the reason for {@link NetworkKick}
     */
    public static String reason(String text) {
        String plain = COLOUR_CODES.matcher(Objects.requireNonNull(text, "text")).replaceAll("").strip();
        String bare = plain.toLowerCase(Locale.ROOT);
        while (bare.endsWith(".") || bare.endsWith("!")) {
            bare = bare.substring(0, bare.length() - 1);
        }
        if (KickClassifier.NEVER_TEXTS.contains(bare)) {
            return "";
        }
        if (plain.length() <= NetworkKick.MAX_REASON_LENGTH) {
            return plain;
        }
        int end = NetworkKick.MAX_REASON_LENGTH;
        if (Character.isHighSurrogate(plain.charAt(end - 1))) {
            end--;
        }
        return plain.substring(0, end);
    }

    /**
     * The message a backend sends for a network kick.
     *
     * @param player  the player
     * @param service the backend's own service id
     * @param text    the kick reason as plain text (see {@link #reason})
     * @return the message
     */
    public static NetworkKick message(UUID player, ServiceId service, String text) {
        return new NetworkKick(player, service, reason(text));
    }
}
