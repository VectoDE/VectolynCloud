package net.vectolyn.cloud.bridge;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The network kick a backend bridge carries out right now ({@code networkKick}), so that it is
 * reported only if it really happens.
 *
 * <p>The bridge runs the kick through {@link #run}. The platform fires its kick event inside the
 * kick; the bridge's handler at the last priority, for events no plugin cancelled, asks
 * {@link #passed} and only then reports the {@code NetworkKick}. A kick another plugin cancels
 * is never reported, so the proxy does not take a player off the network who stays on the server.
 *
 * <p>For the server thread only; a kick inside a kick (a plugin kicking someone else from its
 * event handler) is handled.
 */
public final class OwnKick {

    private UUID player;
    private String reason;
    private boolean passed;

    /**
     * Carries out a kick.
     *
     * @param player the player
     * @param reason the reason to report
     * @param kick   kicks the player; the platform fires its kick event inside
     * @return whether the kick event went through ({@link #passed} was asked for this player)
     */
    public boolean run(UUID player, String reason, Runnable kick) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(kick, "kick");
        UUID outerPlayer = this.player;
        String outerReason = this.reason;
        boolean outerPassed = this.passed;
        this.player = player;
        this.reason = reason;
        this.passed = false;
        try {
            kick.run();
            return this.passed;
        } finally {
            this.player = outerPlayer;
            this.reason = outerReason;
            this.passed = outerPassed;
        }
    }

    /**
     * Whether the bridge is kicking this player right now; the command line watch then stays out.
     *
     * @param kicked the kicked player
     * @return whether {@link #run} is kicking them
     */
    public boolean running(UUID kicked) {
        return player != null && player.equals(kicked);
    }

    /**
     * A kick event went through (nobody cancelled it).
     *
     * @param kicked the kicked player
     * @return the reason to report if it is the kick of {@link #run} (once); empty for any other kick
     */
    public Optional<String> passed(UUID kicked) {
        if (player == null || !player.equals(kicked) || passed) {
            return Optional.empty();
        }
        passed = true;
        return Optional.of(reason);
    }
}
