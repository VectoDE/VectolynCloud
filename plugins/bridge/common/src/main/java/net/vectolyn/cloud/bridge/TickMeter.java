package net.vectolyn.cloud.bridge;

/**
 * TPS and MSPT from the end of each server tick, for servers without a TPS API (PandaSpigot 1.8.8
 * only has {@code ServerTickEndEvent}). Keeps the last {@value #WINDOW} ticks.
 *
 * <ul>
 *   <li>MSPT: average tick duration over the window.</li>
 *   <li>TPS: ticks in the window divided by the time they took, at most {@value #MAX_TPS}.</li>
 * </ul>
 *
 * <p>Thread-safe; usually fed and read on the server thread.
 */
public final class TickMeter {

    /** Ticks kept: five seconds at full speed. */
    public static final int WINDOW = 100;

    /** Highest TPS reported; the server never runs faster on purpose. */
    public static final double MAX_TPS = 20.0;

    private final long[] endNanos = new long[WINDOW];
    private final double[] durations = new double[WINDOW];
    private int next;
    private int count;
    private double durationSum;

    /**
     * Records one tick.
     *
     * @param nowNanos {@link System#nanoTime()} at the end of the tick
     * @param durationMillis how long the tick took; negative or NaN counts as 0
     */
    public synchronized void tick(long nowNanos, double durationMillis) {
        double duration = Double.isNaN(durationMillis) || durationMillis < 0 ? 0 : durationMillis;
        if (count == WINDOW) {
            durationSum -= durations[next];
        } else {
            count++;
        }
        endNanos[next] = nowNanos;
        durations[next] = duration;
        durationSum += duration;
        next = (next + 1) % WINDOW;
    }

    /** Average milliseconds per tick, 0 before the first tick. */
    public synchronized double mspt() {
        return count == 0 ? 0 : Math.max(0, durationSum / count);
    }

    /** Ticks per second over the window, {@value #MAX_TPS} before there are two ticks. */
    public synchronized double tps() {
        if (count < 2) {
            return MAX_TPS;
        }
        int newest = (next - 1 + WINDOW) % WINDOW;
        int oldest = count == WINDOW ? next : 0;
        long elapsed = endNanos[newest] - endNanos[oldest];
        if (elapsed <= 0) {
            return MAX_TPS;
        }
        double tps = (count - 1) * 1_000_000_000.0 / elapsed;
        return Math.min(MAX_TPS, tps);
    }
}
