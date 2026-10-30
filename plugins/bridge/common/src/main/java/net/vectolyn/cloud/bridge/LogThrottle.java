package net.vectolyn.cloud.bridge;

import java.util.function.LongSupplier;

/**
 * Lets at most a fixed number of log lines through per interval, so that someone hammering a
 * backend port with forged logins cannot flood the server log. Lines over the limit are counted
 * and the count is reported with the next line that gets through.
 */
public final class LogThrottle {

    private final int perInterval;
    private final long intervalNanos;
    private final LongSupplier clock;
    private long windowStart;
    private int inWindow;
    private long suppressed;
    private boolean started;

    /** At most {@code perInterval} lines per {@code intervalNanos}, timed with {@link System#nanoTime()}. */
    public LogThrottle(int perInterval, long intervalNanos) {
        this(perInterval, intervalNanos, System::nanoTime);
    }

    /** Same with a clock of its own, for tests. */
    public LogThrottle(int perInterval, long intervalNanos, LongSupplier clock) {
        if (perInterval < 1 || intervalNanos < 1) {
            throw new IllegalArgumentException("limits must be positive");
        }
        this.perInterval = perInterval;
        this.intervalNanos = intervalNanos;
        this.clock = clock;
    }

    /**
     * Asks whether a line may be logged now.
     *
     * @return -1 if the line is to be dropped, otherwise the number of lines dropped since the
     *     last one that got through (usually 0)
     */
    public synchronized long acquire() {
        long now = clock.getAsLong();
        if (!started || now - windowStart >= intervalNanos) {
            started = true;
            windowStart = now;
            inWindow = 0;
        }
        if (inWindow >= perInterval) {
            suppressed++;
            return -1;
        }
        inWindow++;
        long dropped = suppressed;
        suppressed = 0;
        return dropped;
    }
}
