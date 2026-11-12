// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Token buckets per key (an IP address or a token id). A bucket holds up to {@code burst}
 * requests and refills at {@code perMinute} requests a minute.
 *
 * <p>Thread-safe. The map is bounded: it holds at most {@link #MAX_KEYS} keys (or the number given
 * to the constructor) in order of last use. A new key beyond that evicts the bucket that was used
 * longest ago, in constant time. A flood of new addresses therefore never locks out a new
 * client, and no request walks the whole map. An evicted key starts again with a full bucket; to
 * profit from that, a client would need more addresses than the map holds keys, and each of them
 * gets its own bucket anyway.
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
    /** Access order: the eldest entry is the bucket used longest ago. Guarded by itself. */
    private final LinkedHashMap<K, Bucket> buckets;

    /**
     * Creates a limiter that tracks up to {@link #MAX_KEYS} keys.
     *
     * @param burst     requests allowed at once, at least 1
     * @param perMinute refill per minute, at least 1
     * @param nanoTime  monotonic clock in nanoseconds ({@code System::nanoTime}; tests pass their own)
     */
    public RateLimiter(int burst, int perMinute, LongSupplier nanoTime) {
        this(burst, perMinute, nanoTime, MAX_KEYS);
    }

    /**
     * Creates a limiter.
     *
     * @param burst     requests allowed at once, at least 1
     * @param perMinute refill per minute, at least 1
     * @param nanoTime  monotonic clock in nanoseconds
     * @param maxKeys   largest number of keys tracked at once, at least 1
     */
    public RateLimiter(int burst, int perMinute, LongSupplier nanoTime, int maxKeys) {
        if (burst < 1 || perMinute < 1 || maxKeys < 1) {
            throw new IllegalArgumentException("burst, perMinute and maxKeys must be at least 1");
        }
        this.burst = burst;
        this.perMinute = perMinute;
        this.nanoTime = nanoTime;
        this.buckets = new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Bucket> eldest) {
                return size() > maxKeys;
            }
        };
    }

    /**
     * Takes one request from the bucket of {@code key}.
     *
     * @param key the key
     * @return 0 if the request may go ahead, otherwise the seconds until the next one may (at least 1)
     */
    public long tryAcquire(K key) {
        long now = nanoTime.getAsLong();
        Bucket bucket;
        synchronized (buckets) {
            bucket = buckets.get(key);
            if (bucket == null) {
                bucket = new Bucket(burst, now);
                buckets.put(key, bucket); // evicts the eldest bucket if the map is full
            }
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
        Bucket bucket;
        synchronized (buckets) {
            bucket = buckets.get(key);
        }
        return bucket == null ? 0 : bucket.peek(nanoTime.getAsLong());
    }

    /**
     * How many requests the bucket of {@code key} holds now, for the {@code X-RateLimit-Remaining}
     * header. Takes nothing.
     *
     * @param key the key
     * @return the requests left now (rounded down)
     */
    public int remaining(K key) {
        Bucket bucket;
        synchronized (buckets) {
            bucket = buckets.get(key);
        }
        return bucket == null ? burst : bucket.remaining(nanoTime.getAsLong());
    }

    /**
     * Size of a bucket.
     *
     * @return the burst
     */
    public int burst() {
        return burst;
    }

    /**
     * Refill per minute.
     *
     * @return the requests per minute
     */
    public int perMinute() {
        return perMinute;
    }

    /** Number of tracked keys (tests). */
    int size() {
        synchronized (buckets) {
            return buckets.size();
        }
    }

    /** Whether a key is tracked (tests). */
    boolean tracks(K key) {
        synchronized (buckets) {
            return buckets.containsKey(key);
        }
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

        synchronized int remaining(long now) {
            refill(now);
            return (int) Math.floor(tokens);
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
