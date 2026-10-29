package net.vectora.cloud.bridge;

import java.util.List;
import java.util.Objects;

/**
 * Why a backend kicked a player, without a chat component type: the translation keys in the reason
 * (in order, arguments included) and its plain text. The Velocity bridge flattens the component
 * into this.
 *
 * @param present whether the backend sent a reason at all; a lost connection has none
 * @param keys    the translation keys of the reason
 * @param text    the plain text of the reason, without the translated parts
 */
public record KickReason(boolean present, List<String> keys, String text) {

    private static final KickReason MISSING = new KickReason(false, List.of(), "");

    /** Copies the keys. */
    public KickReason {
        keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
        Objects.requireNonNull(text, "text");
    }

    /** No reason: the connection to the backend was lost. */
    public static KickReason missing() {
        return MISSING;
    }

    /**
     * A reason the backend sent.
     *
     * @param keys translation keys in the reason
     * @param text its plain text
     * @return the reason
     */
    public static KickReason of(List<String> keys, String text) {
        return new KickReason(true, keys, text);
    }
}
