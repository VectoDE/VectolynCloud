package net.vectolyn.cloud.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LogThrottleTest {

    @Test
    void limitsLinesPerIntervalAndReportsTheDroppedCount() {
        AtomicLong now = new AtomicLong();
        LogThrottle throttle = new LogThrottle(2, 1_000, now::get);
        assertEquals(0, throttle.acquire());
        assertEquals(0, throttle.acquire());
        assertEquals(-1, throttle.acquire());
        assertEquals(-1, throttle.acquire());
        now.set(1_000);
        assertEquals(2, throttle.acquire(), "first line of the new interval reports the dropped ones");
        assertEquals(0, throttle.acquire());
        assertEquals(-1, throttle.acquire());
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThrows(IllegalArgumentException.class, () -> new LogThrottle(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new LogThrottle(1, 0));
    }
}
