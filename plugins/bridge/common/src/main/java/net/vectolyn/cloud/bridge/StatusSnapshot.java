package net.vectolyn.cloud.bridge;

/**
 * Figures of one status report, taken on the server thread and sent later from the sender thread.
 * {@code CloudClient} clamps values outside the protocol ranges and adds the heap figures.
 *
 * @param playersOnline players connected now
 * @param maxPlayers configured player limit
 * @param tps ticks per second (a proxy reports {@value #PROXY_TPS})
 * @param mspt milliseconds per tick (a proxy reports 0)
 */
public record StatusSnapshot(int playersOnline, int maxPlayers, double tps, double mspt) {

    /** TPS a proxy reports: it has no game loop, and 20 reads as "healthy" everywhere. */
    public static final double PROXY_TPS = 20.0;

    /** A proxy's report: players and limit, no tick figures. */
    public static StatusSnapshot proxy(int playersOnline, int maxPlayers) {
        return new StatusSnapshot(playersOnline, maxPlayers, PROXY_TPS, 0.0);
    }
}
