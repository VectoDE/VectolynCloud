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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class KickMarksTest {

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

    @Test
    void aMarkCountsForTenSeconds() {
        assertEquals(Duration.ofSeconds(10), KickMarks.WINDOW);
        assertFalse(marks.mark(alex, "Spam"), "nobody waits: the caller disconnects");
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
    void aWaitingKickGetsAMarkThatArrivesLater() throws Exception {
        CompletableFuture<Optional<String>> waiting = marks.await(alex, Duration.ofSeconds(30));
        assertFalse(waiting.isDone());
        assertTrue(marks.mark(alex, "Spam"), "the waiting kick takes it over");
        assertEquals(Optional.of("Spam"), waiting.get(1, TimeUnit.SECONDS));
        // A mark that is already there completes the wait at once.
        assertEquals(Optional.of("Spam"), marks.await(alex, Duration.ofSeconds(30)).getNow(null));
    }

    @Test
    void withoutAMarkTheWaitEndsEmpty() throws Exception {
        CompletableFuture<Optional<String>> waiting = marks.await(alex, Duration.ofMillis(20));
        assertEquals(Optional.empty(), waiting.get(5, TimeUnit.SECONDS));
        assertFalse(marks.mark(alex, "Spam"), "the wait is over: the caller disconnects");
    }

    @Test
    void leavingTheProxyForgetsEverythingAndEndsTheWait() throws Exception {
        marks.kickedFrom(alex, "lobby-1");
        marks.mark(alex, "Spam");
        marks.forget(alex);
        assertEquals(Optional.empty(), marks.find(alex));
        assertFalse(marks.concerns(alex, "lobby-1", Optional.empty()));
        CompletableFuture<Optional<String>> waiting = marks.await(alex, Duration.ofSeconds(30));
        marks.forget(alex);
        assertEquals(Optional.empty(), waiting.get(1, TimeUnit.SECONDS));
    }
}
