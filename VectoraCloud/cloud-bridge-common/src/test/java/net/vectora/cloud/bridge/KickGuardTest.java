package net.vectora.cloud.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    void aRescueChecksPlansAndRecordsInOneStep() {
        KickGuard.Attempt<String> first = guard.rescue(alex, "bedwars-1", exclude -> {
            assertEquals(Set.of("bedwars-1"), exclude, "the kicking server is skipped");
            return Optional.of("bedwars-2");
        }, server -> server);
        assertEquals(new KickGuard.Attempt<>(false, Optional.of("bedwars-2")), first);
        KickGuard.Attempt<String> none = guard.rescue(alex, "bedwars-2", exclude -> {
            assertEquals(Set.of("bedwars-1", "bedwars-2"), exclude, "and every server tried before");
            return Optional.empty();
        }, server -> server);
        assertEquals(new KickGuard.Attempt<>(false, Optional.<String>empty()), none);
        assertEquals(Set.of("bedwars-1", "bedwars-2"), guard.tried(alex), "nothing planned, nothing recorded");
        guard.rescue(alex, "bedwars-2", exclude -> Optional.of("lobby"), server -> server);
        guard.rescue(alex, "lobby", exclude -> Optional.of("lobby-2"), server -> server);
        AtomicInteger planned = new AtomicInteger();
        KickGuard.Attempt<String> limited = guard.rescue(alex, "lobby-2", exclude -> {
            planned.incrementAndGet();
            return Optional.of("lobby-3");
        }, server -> server);
        assertEquals(new KickGuard.Attempt<>(true, Optional.<String>empty()), limited, "the fourth");
        assertEquals(0, planned.get(), "no plan once the limit is reached");
    }

    @Test
    void kicksOnSeveralThreadsAtOnceNeverCatchMoreThanTheLimit() throws InterruptedException {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger caught = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                String from = "server-" + i;
                pool.execute(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    KickGuard.Attempt<String> attempt = guard.rescue(alex, from, exclude -> {
                        // Widen the gap between check and record that a split guard would leave open.
                        Thread.onSpinWait();
                        return Optional.of(from + "-target");
                    }, server -> server);
                    if (attempt.result().isPresent()) {
                        caught.incrementAndGet();
                    }
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(KickGuard.MAX_RESCUES, caught.get());
        assertFalse(guard.mayRescue(alex));
    }

    @Test
    void reachingABackendFreesTheServersButKeepsTheLimit() {
        guard.rescued(alex, "lobby-1", "lobby-2");
        guard.arrived(alex);
        assertEquals(Set.of(), guard.tried(alex), "lobby-1 may take him again");
        guard.rescued(alex, "lobby-2", "lobby-1");
        guard.rescued(alex, "lobby-1", "lobby-2");
        guard.arrived(alex);
        assertFalse(guard.mayRescue(alex), "three caught kicks still count");
        // Counter-check: without an arrival the servers stay excluded.
        UUID steve = UUID.randomUUID();
        guard.rescued(steve, "lobby-1", "lobby-2");
        assertEquals(Set.of("lobby-1", "lobby-2"), guard.tried(steve));
        guard.arrived(UUID.randomUUID());
        assertEquals(Set.of("lobby-1", "lobby-2"), guard.tried(steve), "per player");
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
