package net.vectolyn.cloud.api.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Token buckets per key (an IP address or a token id). A bucket holds up to {@code burst}
 * requests and refills at {@code perMinute} requests a minute.
 *
 * <p>Thread-safe. The map is bounded: once it holds {@link #MAX_KEYS} keys, full (idle) buckets
 * are dropped; if every bucket is in use, new keys are refused until some refill, so a flood of
 * new addresses cannot grow the memory of the node.
 *
 * @param <K> the key type
 */
public final class RateLimiter<K> {

    /** Largest number of keys tracked at once. */
    public static final int MAX_KEYS = 10_000;

    private static final long NANOS_PER_MINUTE = 60_000_000_000L;

    private final int burst;
    private final int perMinute;
    private final LongSupplier nanoTime;
    private final Map<K, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Creates a limiter.
     *
     * @param burst     requests allowed at once, at least 1
     * @param perMinute refill per minute, at least 1
     * @param nanoTime  monotonic clock in nanoseconds ({@code System::nanoTime}; tests pass their own)
     */
    public RateLimiter(int burst, int perMinute, LongSupplier nanoTime) {
        if (burst < 1 || perMinute < 1) {
            throw new IllegalArgumentException("burst and perMinute must be at least 1");
        }
        this.burst = burst;
        this.perMinute = perMinute;
        this.nanoTime = nanoTime;
    }

    /**
     * Takes one request from the bucket of {@code key}.
     *
     * @param key the key
     * @return 0 if the request may go ahead, otherwise the seconds until the next one may (at least 1)
     */
    public long tryAcquire(K key) {
        long now = nanoTime.getAsLong();
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= MAX_KEYS) {
                prune(now);
                if (buckets.size() >= MAX_KEYS) {
                    return 1;
                }
            }
            bucket = buckets.computeIfAbsent(key, k -> new Bucket(burst, now));
        }
        return bucket.take(now);
    }

    /**
     * Whether the bucket of {@code key} is empty, without taking from it.
     *
     * @param key the key
     * @return the seconds until the next request may go ahead, or 0 if one may now
     */
    public long peek(K key) {
        Bucket bucket = buckets.get(key);
        return bucket == null ? 0 : bucket.peek(nanoTime.getAsLong());
    }

    /** Number of tracked keys (tests). */
    int size() {
        return buckets.size();
    }

    private void prune(long now) {
        buckets.entrySet().removeIf(e -> e.getValue().isFull(now));
    }

    private final class Bucket {

        private double tokens;
        private long updated;

        Bucket(double tokens, long updated) {
            this.tokens = tokens;
            this.updated = updated;
        }

        synchronized long take(long now) {
            refill(now);
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return waitSeconds();
        }

        synchronized long peek(long now) {
            refill(now);
            return tokens >= 1 ? 0 : waitSeconds();
        }

        synchronized boolean isFull(long now) {
            refill(now);
            return tokens >= burst;
        }

        private void refill(long now) {
            long elapsed = Math.max(0, now - updated);
            updated = now;
            tokens = Math.min(burst, tokens + (double) elapsed * perMinute / NANOS_PER_MINUTE);
        }

        private long waitSeconds() {
            double missing = 1 - tokens;
            return Math.max(1, (long) Math.ceil(missing * 60.0 / perMinute));
        }
    }
}
