package net.vectora.cloud.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KickGuardTest {

    private Instant now = Instant.parse("2026-10-29T12:00:00Z");
    private final KickGuard guard = new KickGuard(new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    });
    private final UUID alex = UUID.randomUUID();

    @Test
    void theServersOfAnIncidentAreTriedOnlyOnce() {
        assertEquals(Set.of(), guard.tried(alex));
        guard.rescued(alex, "bedwars-1", "bedwars-2");
        assertEquals(Set.of("bedwars-1", "bedwars-2"), guard.tried(alex));
        guard.rescued(alex, "bedwars-2", "lobby");
        assertEquals(Set.of("bedwars-1", "bedwars-2", "lobby"), guard.tried(alex));
        assertEquals(Set.of(), guard.tried(UUID.randomUUID()), "per player");
    }

    @Test
    void theIncidentEndsAWindowAfterTheLastRescue() {
        guard.rescued(alex, "bedwars-1", "bedwars-2");
        now = now.plus(KickGuard.WINDOW).minusSeconds(1);
        assertEquals(Set.of("bedwars-1", "bedwars-2"), guard.tried(alex), "one second before the end");
        now = now.plusSeconds(1);
        assertEquals(Set.of(), guard.tried(alex), "at the end");
    }

    @Test
    void atMostThreeRescuesPerPlayerInSixtySeconds() {
        assertEquals(3, KickGuard.MAX_RESCUES);
        assertEquals(Duration.ofSeconds(60), KickGuard.WINDOW);
        for (int i = 0; i < KickGuard.MAX_RESCUES; i++) {
            assertTrue(guard.mayRescue(alex), "rescue " + (i + 1));
            guard.rescued(alex, "s" + i, "t" + i);
            now = now.plusSeconds(10);
        }
        assertFalse(guard.mayRescue(alex), "the fourth within 60 s");
        assertTrue(guard.mayRescue(UUID.randomUUID()), "another player");
        // Counter-check: once the first rescue is 60 s old, one more is allowed.
        now = now.minusSeconds(30).plus(KickGuard.WINDOW).minusSeconds(1);
        assertFalse(guard.mayRescue(alex), "the first is 59 s old");
        now = now.plusSeconds(1);
        assertTrue(guard.mayRescue(alex), "the first is 60 s old");
    }

    @Test
    void leavingTheProxyEndsEverything() {
        for (int i = 0; i < KickGuard.MAX_RESCUES; i++) {
            guard.rescued(alex, "s" + i, "t" + i);
        }
        assertFalse(guard.mayRescue(alex));
        guard.forget(alex);
        assertTrue(guard.mayRescue(alex));
        assertEquals(Set.of(), guard.tried(alex));
    }
}
