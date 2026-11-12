// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private final AtomicLong now = new AtomicLong();

    @Test
    void burstThenRefill() {
        RateLimiter<String> limiter = new RateLimiter<>(3, 60, now::get);
        assertEquals(0, limiter.tryAcquire("a"));
        assertEquals(0, limiter.tryAcquire("a"));
        assertEquals(0, limiter.tryAcquire("a"));
        assertEquals(1, limiter.tryAcquire("a"));
        assertEquals(0, limiter.tryAcquire("b"), "keys are independent");
        now.addAndGet(1_000_000_000L);
        assertEquals(0, limiter.tryAcquire("a"));
        assertEquals(1, limiter.tryAcquire("a"));
        assertEquals(1, limiter.peek("a"));
        assertEquals(0, limiter.peek("unknown"));
    }

    @Test
    void retryAfterGrowsWithASlowRefill() {
        RateLimiter<String> limiter = new RateLimiter<>(1, 2, now::get);
        assertEquals(0, limiter.tryAcquire("a"));
        assertEquals(30, limiter.tryAcquire("a"));
    }

    @Test
    void remainingCountsDownWithoutTaking() {
        RateLimiter<String> limiter = new RateLimiter<>(3, 60, now::get);
        assertEquals(3, limiter.remaining("a"));
        limiter.tryAcquire("a");
        assertEquals(2, limiter.remaining("a"));
        assertEquals(2, limiter.remaining("a"));
    }

    @Test
    void aFullMapStillLetsNewClientsThrough() {
        RateLimiter<Integer> limiter = new RateLimiter<>(1, 1, now::get);
        // More keys than the map holds, every bucket empty and none refilled (the clock stands still).
        for (int i = 0; i < RateLimiter.MAX_KEYS + 500; i++) {
            assertEquals(0, limiter.tryAcquire(i), "new client " + i + " must get through");
        }
        assertEquals(RateLimiter.MAX_KEYS, limiter.size(), "the map stays bounded");
        // The keys used longest ago were evicted, the recent ones keep their (empty) buckets.
        assertFalse(limiter.tracks(0));
        assertTrue(limiter.tracks(RateLimiter.MAX_KEYS + 499));
        assertEquals(60, limiter.tryAcquire(RateLimiter.MAX_KEYS + 499));
    }

    @Test
    void aRecentlyUsedKeyIsNotEvicted() {
        RateLimiter<Integer> limiter = new RateLimiter<>(1, 1, now::get, 3);
        limiter.tryAcquire(1);
        limiter.tryAcquire(2);
        limiter.tryAcquire(3);
        limiter.peek(1); // 1 is now the most recently used
        limiter.tryAcquire(4);
        assertTrue(limiter.tracks(1));
        assertFalse(limiter.tracks(2));
        assertEquals(60, limiter.tryAcquire(1), "the bucket of a client in use is kept, still empty");
    }

    @Test
    void newKeysOnAFullMapTakeConstantTime() {
        RateLimiter<Integer> limiter = new RateLimiter<>(1, 1, now::get);
        for (int i = 0; i < RateLimiter.MAX_KEYS; i++) {
            limiter.tryAcquire(i);
        }
        // With a scan of the whole map per new key this would be 10^9 bucket checks.
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (int i = 0; i < 100_000; i++) {
                limiter.tryAcquire(-1 - i);
            }
        });
        assertEquals(RateLimiter.MAX_KEYS, limiter.size());
    }
}
