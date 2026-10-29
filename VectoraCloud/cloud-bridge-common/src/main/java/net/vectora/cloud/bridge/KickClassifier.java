package net.vectora.cloud.bridge;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whether the proxy catches a kick and sends the player to another server: only when the backend
 * went away (stop, crash, lost connection, timeout, error) or is full, never for a ban, the
 * whitelist or a kick someone meant.
 *
 * <ol>
 *   <li>A ban, whitelist or kick anywhere in the reason (key or word) is never caught.</li>
 *   <li>No reason at all is a lost connection: caught.</li>
 *   <li>A known key ({@link #RESCUED_KEYS}) or a known text ({@link #RESCUED_TEXTS}, case and
 *       colour codes ignored, or starting with {@code Internal Exception:}) is caught.</li>
 *   <li>Anything else is not caught. Admins and plugins kick with their own words, so an unknown
 *       text counts as meant.</li>
 * </ol>
 */
public final class KickClassifier {

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

    /** Key prefixes that are never caught: bans, whitelist, kicks. */
    public static final Set<String> NEVER_KEY_PREFIXES = Set.of(
            "multiplayer.disconnect.banned",
            "multiplayer.disconnect.not_whitelisted",
            "multiplayer.disconnect.kicked");

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
            "\\b(ban|bans|banned|bann|gebannt|gesperrt|kick|kicked|gekickt|whitelist|whitelisted|white-listed)\\b");
    private static final Pattern COLOUR_CODES = Pattern.compile("§.");

    private KickClassifier() {
    }

    /**
     * Whether the kick is caught.
     *
     * @param reason the reason of the kick
     * @return {@code true} to send the player to another server
     */
    public static boolean rescues(KickReason reason) {
        Objects.requireNonNull(reason, "reason");
        if (!reason.present()) {
            return true;
        }
        String text = COLOUR_CODES.matcher(reason.text()).replaceAll("").trim().toLowerCase(Locale.ROOT);
        for (String key : reason.keys()) {
            if (NEVER_KEY_PREFIXES.stream().anyMatch(key::startsWith)) {
                return false;
            }
        }
        if (NEVER_WORDS.matcher(text).find()) {
            return false;
        }
        if (reason.keys().stream().anyMatch(RESCUED_KEYS::contains)) {
            return true;
        }
        String bare = text.replaceAll("[.!]+$", "");
        return RESCUED_TEXTS.contains(bare) || text.startsWith(INTERNAL_EXCEPTION);
    }
}
