package net.vectora.cloud.bridge;

import java.util.Objects;

/**
 * What the proxy does with a kick (see {@link ProxyBridge#kicked}).
 *
 * @param action     keep the proxy's result, move the player, or disconnect them
 * @param server     the target of {@link Action#MOVE}, otherwise empty
 * @param message    the line for the player: the notice of a move, or the text of a disconnect
 *                   when the backend gave no reason; empty for {@link Action#KEEP}
 * @param showReason for {@link Action#MOVE}: the player reads the backend's original kick reason
 *                   after the move instead of the notice (the notice stays the fallback when the
 *                   platform has no reason at hand)
 */
public record KickOutcome(Action action, String server, String message, boolean showReason) {

    /** What happens. */
    public enum Action {
        /** Leave the kick as the proxy or another plugin decided. */
        KEEP,
        /** Send the player to {@link #server()}. */
        MOVE,
        /** Disconnect the player with the original reason. */
        DISCONNECT
    }

    private static final KickOutcome KEEP_IT = new KickOutcome(Action.KEEP, "", "", false);

    /** Checks the parts. */
    public KickOutcome {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(message, "message");
    }

    /** Leave the kick alone. */
    public static KickOutcome keep() {
        return KEEP_IT;
    }

    /**
     * Send the player on.
     *
     * @param server  the target
     * @param message the notice for the player
     * @return the outcome
     */
    public static KickOutcome move(String server, String message) {
        return new KickOutcome(Action.MOVE, server, message, false);
    }

    /**
     * Send the player on and show them the backend's original kick reason after the move.
     *
     * @param server   the target
     * @param fallback the notice if the platform has no reason at hand
     * @return the outcome
     */
    public static KickOutcome moveShowingReason(String server, String fallback) {
        return new KickOutcome(Action.MOVE, server, fallback, true);
    }

    /**
     * Disconnect the player.
     *
     * @param message the text when the backend gave no reason
     * @return the outcome
     */
    public static KickOutcome disconnect(String message) {
        return new KickOutcome(Action.DISCONNECT, "", message, false);
    }
}
