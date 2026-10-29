package net.vectora.cloud.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class BackoffTest {

    @Test
    void doublesUpToTheMaximumWithJitter() {
        Backoff backoff = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(30), new SplittableRandom(1));
        long[] steps = {1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000};
        for (long step : steps) {
            long wait = backoff.nextMillis();
            assertTrue(wait >= step / 2 && wait <= step, "wait " + wait + " for step " + step);
        }
    }

    @Test
    void resetStartsAgainAtTheMinimum() {
        Backoff backoff = new Backoff(Duration.ofMillis(100), Duration.ofSeconds(10), new SplittableRandom(2));
        for (int i = 0; i < 10; i++) {
            backoff.nextMillis();
        }
        backoff.reset();
        long wait = backoff.nextMillis();
        assertTrue(wait >= 50 && wait <= 100, String.valueOf(wait));
    }

    @Test
    void equalBoundsGiveAStableRange() {
        Backoff backoff = new Backoff(Duration.ofMillis(1), Duration.ofMillis(1), new SplittableRandom(3));
        for (int i = 0; i < 5; i++) {
            long wait = backoff.nextMillis();
            assertTrue(wait == 0 || wait == 1);
        }
        assertEquals(0, new Backoff(Duration.ZERO, Duration.ZERO, new SplittableRandom(4)).nextMillis());
    }
}
