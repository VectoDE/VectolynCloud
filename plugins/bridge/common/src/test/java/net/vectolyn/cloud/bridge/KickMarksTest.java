package net.vectolyn.cloud.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class KickMarksTest {

    private static final Optional<String> ON_LOBBY_1 = Optional.of("lobby-1");

    private Instant now = Instant.parse("2026-10-29T12:00:00Z");
    private final KickMarks marks = new KickMarks(new Clock() {
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

    private KickMarks.Outcome mark(String reason) {
        return marks.mark(alex, "lobby-1", ON_LOBBY_1, reason);
    }

    @Test
    void aMarkCountsForTenSeconds() {
        assertEquals(Duration.ofSeconds(10), KickMarks.WINDOW);
        assertEquals(KickMarks.Outcome.MARKED, mark("Spam"), "nobody waits: the caller disconnects");
        now = now.plusSeconds(9);
        assertEquals(Optional.of("Spam"), marks.find(alex));
        assertEquals(Optional.empty(), marks.find(UUID.randomUUID()), "per player");
        now = now.plusSeconds(1);
        assertEquals(Optional.empty(), marks.find(alex), "gone after 10 s");
    }

    @Test
    void aMarkConcernsOnlyTheServerThePlayerIsOnOrWasKickedFrom() {
        assertTrue(marks.concerns(alex, "lobby-1", Optional.of("lobby-1")), "on it");
        assertFalse(marks.concerns(alex, "lobby-1", Optional.of("lobby-2")), "on another server");
        assertFalse(marks.concerns(alex, "lobby-1", Optional.empty()), "nowhere and not kicked");
        marks.kickedFrom(alex, "lobby-1");
        assertTrue(marks.concerns(alex, "lobby-1", Optional.of("lobby-2")), "kicked from it, moved on since");
        assertFalse(marks.concerns(alex, "bedwars-1", Optional.of("lobby-2")), "kicked from another one");
        now = now.plusSeconds(10);
        assertFalse(marks.concerns(alex, "lobby-1", Optional.of("lobby-2")), "the kick is older than 10 s");
    }

    @Test
    void aMarkThatDoesNotConcernThePlayerLeavesNothingBehind() throws Exception {
        assertEquals(KickMarks.Outcome.IGNORED, marks.mark(alex, "bedwars-1", ON_LOBBY_1, "Spam"));
        assertEquals(Optional.empty(), marks.find(alex), "no entry is left");
        CompletableFuture<Optional<String>> waiting = marks.await(alex, Duration.ofSeconds(30));
        assertEquals(KickMarks.Outcome.IGNORED, marks.mark(alex, "bedwars-1", ON_LOBBY_1, "Spam"));
        assertFalse(waiting.isDone(), "a waiting kick does not take a foreign mark");
        marks.forget(alex);
    }

    @Test
    void checkAndRecordAreOneStepUnderConcurrentMarks() throws Exception {
        // 16 threads mark at once, half of them from a server the player was never on: exactly the
        // concerning ones are recorded, and never a foreign reason.
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<KickMarks.Outcome>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 16; i++) {
                boolean own = i % 2 == 0;
                results.add(pool.submit(() -> {
                    start.await();
                    return marks.mark(alex, own ? "lobby-1" : "bedwars-1", ON_LOBBY_1, own ? "own" : "foreign");
                }));
            }
            start.countDown();
            int ignored = 0;
            for (Future<KickMarks.Outcome> result : results) {
                if (result.get(5, TimeUnit.SECONDS) == KickMarks.Outcome.IGNORED) {
                    ignored++;
                }
            }
            assertEquals(8, ignored);
            assertEquals(Optional.of("own"), marks.find(alex));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aWaitingKickGetsAMarkThatArrivesLater() throws Exception {
        CompletableFuture<Optional<String>> waiting = marks.await(alex, Duration.ofSeconds(30));
        assertFalse(waiting.isDone());
        assertEquals(KickMarks.Outcome.TAKEN, mark("Spam"), "the waiting kick takes it over");
        assertEquals(Optional.of("Spam"), waiting.get(1, TimeUnit.SECONDS));
        // A mark that is already there completes the wait at once.
        assertEquals(Optional.of("Spam"), marks.await(alex, Duration.ofSeconds(30)).getNow(null));
    }

    @Test
    void aSecondWaitEndsTheFirstOutsideTheLock() throws Exception {
        CompletableFuture<Optional<String>> first = marks.await(alex, Duration.ofSeconds(30));
        // The first wait goes on in the thread of the second await. Another thread must reach the
        // store meanwhile: with the lock still held it would block.
        CompletableFuture<Boolean> calledBack = first.thenApply(result ->
                CompletableFuture.supplyAsync(() -> marks.find(alex).isEmpty())
                        .completeOnTimeout(false, 2, TimeUnit.SECONDS).join());
        CompletableFuture<Optional<String>> second = marks.await(alex, Duration.ofSeconds(30));
        assertEquals(Optional.empty(), first.get(1, TimeUnit.SECONDS));
        assertTrue(calledBack.get(1, TimeUnit.SECONDS));
        assertEquals(KickMarks.Outcome.TAKEN, mark("Spam"), "the second wait takes the mark");
        assertEquals(Optional.of("Spam"), second.get(1, TimeUnit.SECONDS));
    }

    @Test
    void withoutAMarkTheWaitEndsEmpty() throws Exception {
        CompletableFuture<Optional<String>> waiting = marks.await(alex, Duration.ofMillis(20));
        assertEquals(Optional.empty(), waiting.get(5, TimeUnit.SECONDS));
        assertEquals(KickMarks.Outcome.MARKED, mark("Spam"), "the wait is over: the caller disconnects");
    }

    @Test
    void reachingABackendForgetsTheMarksButKeepsTheKickingServer() throws Exception {
        marks.kickedFrom(alex, "lobby-1");
        mark("Spam");
        CompletableFuture<Optional<String>> waiting = marks.await(alex, Duration.ofSeconds(30));
        marks.arrived(alex);
        assertEquals(Optional.empty(), marks.find(alex), "the mark is gone");
        assertTrue(marks.concerns(alex, "lobby-1", Optional.of("lobby-2")),
                "a late report from the kicking server still counts");
        CompletableFuture<Optional<String>> next = marks.await(alex, Duration.ofSeconds(30));
        marks.arrived(alex);
        assertEquals(Optional.empty(), next.get(1, TimeUnit.SECONDS), "a waiting kick goes on without a mark");
        assertTrue(waiting.isDone());
    }

    @Test
    void leavingTheProxyForgetsEverythingAndEndsTheWait() throws Exception {
        marks.kickedFrom(alex, "lobby-1");
        mark("Spam");
        marks.forget(alex);
        assertEquals(Optional.empty(), marks.find(alex));
        assertFalse(marks.concerns(alex, "lobby-1", Optional.empty()));
        CompletableFuture<Optional<String>> waiting = marks.await(alex, Duration.ofSeconds(30));
        marks.forget(alex);
        assertEquals(Optional.empty(), waiting.get(1, TimeUnit.SECONDS));
    }
}
