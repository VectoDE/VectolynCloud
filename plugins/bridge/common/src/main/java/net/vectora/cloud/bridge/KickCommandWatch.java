package net.vectora.cloud.bridge;

import java.util.ArrayList;
import java.util.Arrays;
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
 * <p>The bridges replace {@code kick} and {@code minecraft:kick} with a command of their own, which
 * kicks through the moderation's {@code networkKick}; that also covers {@code execute ... run kick},
 * {@code dispatchCommand} and aliases. This watch is the second way: it catches the {@code kick}
 * commands of other plugins ({@code <plugin>:kick}), a {@code /kick} on a server where replacing
 * failed, and on 1.8.8 the selectors the bridge's command hands on to the server ({@link #expect}).
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

    /** Resolves a selector to the names of the players it matches, where the platform can. */
    @FunctionalInterface
    public interface Selectors {
        /** For a platform without a selector API (1.8.8). */
        Selectors NONE = selector -> Optional.empty();

        /**
         * Resolves a selector for the sender of the line.
         *
         * @param selector the selector, such as {@code @a} or {@code @p[r=5]}
         * @return the names of the matched players; empty if the platform cannot resolve it
         */
        Optional<List<String>> resolve(String selector);
    }

    /** One kick command of a line: its target (a name or a selector) and its reason. */
    private record Target(String target, String reason) {
    }

    private final Set<String> names = new HashSet<>();
    /** Reasons ({@link #reason}) of selectors the platform could not resolve. */
    private final Set<String> selectorReasons = new HashSet<>();

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
     * @param selectors     resolves a selector of the line to the players it matches, for its sender
     * @return whether a kick is now expected; the platform then clears the watch at the next tick
     */
    public synchronized boolean command(String line, Source source, boolean hasPermission,
                                        Map<String, List<String>> aliases, Selectors selectors) {
        clear();
        if (!entitled(source, hasPermission)) {
            return false;
        }
        List<Target> found = new ArrayList<>();
        collect(Objects.requireNonNull(line, "line"), Objects.requireNonNull(aliases, "aliases"), 0, found);
        if (found.isEmpty()) {
            return false;
        }
        Objects.requireNonNull(selectors, "selectors");
        for (Target target : found) {
            add(target.target(), target.reason(), selectors);
        }
        return true;
    }

    /**
     * Expects the kick of a target in this tick without reading a command line: for a kick command
     * of the bridge that hands a selector on to the server's own command. The caller has checked
     * the sender ({@link #entitled}) and clears the watch at the next tick ({@link #clear}).
     *
     * @param target    a player name or a selector
     * @param reason    the reason of the command, empty without one
     * @param selectors resolves the selector, where the platform can
     */
    public synchronized void expect(String target, String reason, Selectors selectors) {
        add(Objects.requireNonNull(target, "target"), Objects.requireNonNull(reason, "reason"),
                Objects.requireNonNull(selectors, "selectors"));
    }

    /**
     * A name is expected by itself. A selector is expected through the players it matches; if the
     * platform cannot resolve it (1.8.8), only a kick in this tick whose reason is the command's
     * reason counts, so another kick in the same tick (a plugin's, for flying) is not taken for it.
     */
    private void add(String target, String reason, Selectors selectors) {
        if (!target.startsWith("@")) {
            names.add(target.toLowerCase(Locale.ROOT));
            return;
        }
        Optional<List<String>> matched = selectors.resolve(target);
        if (matched.isPresent()) {
            matched.get().forEach(name -> names.add(name.toLowerCase(Locale.ROOT)));
        } else {
            selectorReasons.add(reason(reason));
        }
    }

    /**
     * A player is being kicked.
     *
     * @param playerName the player's name
     * @param reasonText the kick reason as plain text
     * @return whether the kick comes from the {@code /kick} of an entitled sender in this tick: a
     *     named or resolved target, or for a selector the platform could not resolve a kick with
     *     the command's reason
     */
    public synchronized boolean kicked(String playerName, String reasonText) {
        return names.contains(playerName.toLowerCase(Locale.ROOT))
                || (!selectorReasons.isEmpty() && selectorReasons.contains(reason(reasonText)));
    }

    /** Forgets the current line; at the next tick and before each new line. */
    public synchronized void clear() {
        names.clear();
        selectorReasons.clear();
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
        List<Target> found = new ArrayList<>();
        collect(Objects.requireNonNull(line, "line"), aliases, 0, found);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.stream().map(Target::target).toList());
    }

    /**
     * The reason of a kick command: everything after the target, as vanilla joins it.
     *
     * @param args the arguments after the label, the target first
     * @return the reason, empty without one
     */
    public static String reasonOf(String[] args) {
        return args.length < 2 ? "" : String.join(" ", List.of(args).subList(1, args.length)).strip();
    }

    private static void collect(String line, Map<String, List<String>> aliases, int depth, List<Target> found) {
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
                found.add(new Target(parts[1], reasonOf(Arrays.copyOfRange(parts, 1, parts.length))));
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
            int index;
            try {
                index = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException tooLong) {
                // More digits than an int holds: no argument has that index, like any index too high.
                index = -1;
            }
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
