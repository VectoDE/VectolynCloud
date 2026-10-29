package net.vectora.cloud.bridge;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whether the proxy catches a kick and sends the player to another server. Every kick is caught
 * except a ban, the whitelist, the vanilla {@code /kick} and a kick our own moderation marks
 * ({@link #MODERATION_KEY}).
 *
 * <ol>
 *   <li>The {@linkplain #MODERATION_KEY moderation marker}, a ban or the whitelist anywhere in the
 *       reason (key or word), and the vanilla {@code /kick} (the key
 *       {@code multiplayer.disconnect.kicked} or, on 1.8.8, the whole text
 *       {@code Kicked by an operator.}) are never caught: {@link Verdict#NOT_CAUGHT}. A
 *       {@code /kick} with a reason of its own arrives as that text only and is caught like any
 *       other words.</li>
 *   <li>No reason at all is a lost connection, and an empty one tells nothing either:
 *       {@link Verdict#SERVER_GONE}.</li>
 *   <li>A known key ({@link #RESCUED_KEYS}) or a known text ({@link #RESCUED_TEXTS}, case and
 *       colour codes ignored, or starting with {@code Internal Exception:}) means the server
 *       stopped, broke or is full: {@link Verdict#SERVER_GONE}.</li>
 *   <li>Anything else, a plugin's or an admin's own words or a vanilla key such as
 *       {@code multiplayer.disconnect.idling}, is caught as well, and the player reads the
 *       original reason after the move: {@link Verdict#OTHER_REASON}.</li>
 * </ol>
 */
public final class KickClassifier {

    /**
     * The marker of a kick by VectoraCloud's own moderation: a translation key in the kick
     * reason that is this text or starts with it and a dot ({@code vectora.moderation.kick},
     * {@code vectora.moderation.mute-kick}). The proxy never catches such a kick; the player is
     * disconnected with the reason as it is.
     *
     * <p>The key is reserved for the moderation module; no other plugin may use it. The module
     * puts it anywhere in the reason component, as the component itself or as a child or an
     * argument. So that the player still reads the reason, it uses the fallback text {@code %s}
     * with the visible reason as the only argument, in Adventure:
     * {@code Component.translatable().key(MODERATION_KEY + ".kick").fallback("%s").arguments(reason)}.
     * Clients that know no translation for the key show the fallback, that is the reason.
     */
    public static final String MODERATION_KEY = "vectora.moderation";

    /** How a kick is handled. */
    public enum Verdict {
        /** A ban, the whitelist, the vanilla /kick or a kick our moderation marked: left alone. */
        NOT_CAUGHT,
        /** The server stopped, broke, lost the connection or is full: caught with a short notice. */
        SERVER_GONE,
        /** Any other reason: caught, and the player reads the original reason after the move. */
        OTHER_REASON
    }

    /** Vanilla keys of a stop, a lost or broken connection and a full server. */
    public static final Set<String> RESCUED_KEYS = Set.of(
            "multiplayer.disconnect.server_shutdown",
            "multiplayer.disconnect.server_full",
            "disconnect.timeout",
            "disconnect.genericReason",
            "disconnect.closed",
            "disconnect.lost",
            "disconnect.endOfStream",
            "disconnect.overflow");

    /**
     * Key prefixes that are never caught: bans, the whitelist and the vanilla {@code /kick} without
     * a reason of its own.
     */
    public static final Set<String> NEVER_KEY_PREFIXES = Set.of(
            "multiplayer.disconnect.banned",
            "multiplayer.disconnect.not_whitelisted",
            "multiplayer.disconnect.kicked");

    /**
     * Texts that are never caught, in lower case without the closing punctuation: the plain text of
     * the vanilla {@code /kick} without a reason of its own on 1.8.8 (Spigot, PandaSpigot).
     */
    public static final Set<String> NEVER_TEXTS = Set.of("kicked by an operator");

    /**
     * Texts of Paper, Spigot and 1.8.8 for a stop, a restart, a timeout and a full server, in lower
     * case without the closing punctuation.
     */
    public static final Set<String> RESCUED_TEXTS = Set.of(
            "server closed",
            "server is restarting",
            "server restarting",
            "timed out",
            "the server is full",
            "server is full");

    private static final String INTERNAL_EXCEPTION = "internal exception:";

    private static final Pattern NEVER_WORDS = Pattern.compile(
            "\\b(ban|bans|banned|bann|gebannt|verbannt|verbannen|gesperrt|tempban|tempbanned|permban|permbanned"
                    + "|ipban|ipbanned|banished|whitelist|whitelisted|white-listed)\\b");
    private static final Pattern COLOUR_CODES = Pattern.compile("§.");

    private KickClassifier() {
    }

    /**
     * How the kick is handled.
     *
     * @param reason the reason of the kick
     * @return the verdict
     */
    public static Verdict classify(KickReason reason) {
        Objects.requireNonNull(reason, "reason");
        if (!reason.present()) {
            return Verdict.SERVER_GONE;
        }
        for (String key : reason.keys()) {
            if (isModerationKey(key) || NEVER_KEY_PREFIXES.stream().anyMatch(key::startsWith)) {
                return Verdict.NOT_CAUGHT;
            }
        }
        String text = COLOUR_CODES.matcher(reason.text()).replaceAll("").trim().toLowerCase(Locale.ROOT);
        String bare = withoutClosingPunctuation(text);
        if (NEVER_WORDS.matcher(text).find() || NEVER_TEXTS.contains(bare)) {
            return Verdict.NOT_CAUGHT;
        }
        if (reason.keys().stream().anyMatch(RESCUED_KEYS::contains)) {
            return Verdict.SERVER_GONE;
        }
        if (RESCUED_TEXTS.contains(bare) || text.startsWith(INTERNAL_EXCEPTION)
                || (text.isEmpty() && reason.keys().isEmpty())) {
            return Verdict.SERVER_GONE;
        }
        return Verdict.OTHER_REASON;
    }

    /** The text without the dots and exclamation marks at its end, without a regular expression. */
    private static String withoutClosingPunctuation(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '.' || text.charAt(end - 1) == '!')) {
            end--;
        }
        return text.substring(0, end);
    }

    /**
     * Whether the kick is caught.
     *
     * @param reason the reason of the kick
     * @return {@code true} to send the player to another server
     */
    public static boolean rescues(KickReason reason) {
        return classify(reason) != Verdict.NOT_CAUGHT;
    }

    /**
     * Whether a translation key is the {@linkplain #MODERATION_KEY moderation marker}.
     *
     * @param key a translation key
     * @return whether it is {@value #MODERATION_KEY} or starts with it and a dot
     */
    public static boolean isModerationKey(String key) {
        return key.equals(MODERATION_KEY) || key.startsWith(MODERATION_KEY + ".");
    }
}
