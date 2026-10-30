package net.vectolyn.cloud.api.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void theNumberOfKeysIsBounded() {
        RateLimiter<Integer> limiter = new RateLimiter<>(1, 1, now::get);
        for (int i = 0; i < RateLimiter.MAX_KEYS; i++) {
            limiter.tryAcquire(i);
        }
        // Every bucket is in use: a new key is refused instead of growing the map.
        assertEquals(1, limiter.tryAcquire(-1));
        assertEquals(RateLimiter.MAX_KEYS, limiter.size());
        // Once they refilled they are dropped and new keys fit again.
        now.addAndGet(120_000_000_000L);
        assertEquals(0, limiter.tryAcquire(-1));
        assertTrue(limiter.size() < RateLimiter.MAX_KEYS);
    }
}
