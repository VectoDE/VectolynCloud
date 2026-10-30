package net.vectolyn.cloud.client;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Wait times between reconnects: doubles from the minimum up to the maximum, with jitter, so a
 * restarted cloud is not hit by every service at the same moment. Not thread-safe.
 */
final class Backoff {

    private final long minMillis;
    private final long maxMillis;
    private final RandomGenerator random;
    private long current;

    Backoff(Duration min, Duration max, RandomGenerator random) {
        this.minMillis = min.toMillis();
        this.maxMillis = Math.max(minMillis, max.toMillis());
        this.random = random;
        this.current = minMillis;
    }

    /** Returns the next wait: between half and all of the current step; the step then doubles. */
    long nextMillis() {
        long step = current;
        current = Math.min(maxMillis, current * 2);
        long half = step / 2;
        return half + random.nextLong(step - half + 1);
    }

    /** Starts again at the minimum, after a successful connection. */
    void reset() {
        current = minMillis;
    }
}
