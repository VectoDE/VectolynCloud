package net.vectora.cloud.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.PlayerJoin;
import org.junit.jupiter.api.Test;

class BridgeSenderTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @Test
    void callsRunOnTheSenderThreadNeverOnTheCallingThread() {
        FakeLink link = new FakeLink();
        BridgeSender sender = BridgeSender.start(link);
        Thread caller = Thread.currentThread();

        assertTrue(sender.startLink());
        assertTrue(sender.markReady());
        assertTrue(sender.send(new PlayerJoin(UUID.randomUUID(), "Steve", new ServiceId("lobby-1"), false)));
        assertTrue(sender.reportStatus(new StatusSnapshot(1, 20, 20, 3)));
        sender.close(WAIT);

        assertEquals(List.of("start", "ready", "send", "status", "close"), link.names());
        for (FakeLink.Call call : link.calls) {
            assertNotSame(caller, call.thread(), call.what() + " ran on the calling thread");
            assertEquals(BridgeSender.THREAD_NAME, call.thread().getName());
            assertTrue(call.thread().isDaemon(), "the sender thread must not keep the JVM alive");
        }
    }

    @Test
    void submittingDoesNotWaitForABlockedLink() throws InterruptedException {
        FakeLink link = new FakeLink();
        link.block = true;
        BridgeSender sender = BridgeSender.start(link, 4);
        sender.markReady();
        assertTrue(link.blocked.await(5, TimeUnit.SECONDS));

        // The link hangs like a socket to a stuck cloud; the caller still returns at once.
        assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
            for (int i = 0; i < 100; i++) {
                sender.reportStatus(new StatusSnapshot(i, 20, 20, 1));
                sender.markReady();
            }
        });
        assertTrue(sender.dropped() > 0, "a full queue drops instead of blocking");

        link.release.countDown();
        sender.close(WAIT);
    }

    @Test
    void aFullQueueDropsAndCountsInsteadOfBlocking() throws InterruptedException {
        FakeLink link = new FakeLink();
        link.block = true;
        BridgeSender sender = BridgeSender.start(link, 2);
        sender.markReady();
        assertTrue(link.blocked.await(5, TimeUnit.SECONDS));

        assertTrue(sender.markReady());
        assertTrue(sender.markReady());
        assertFalse(sender.markReady(), "third action does not fit into a queue of two");
        assertEquals(1, sender.dropped());

        link.release.countDown();
        sender.close(WAIT);
        assertEquals(List.of("ready", "ready", "ready", "close"), link.names());
    }

    @Test
    void statusReportsAreCoalescedToTheNewest() throws InterruptedException {
        FakeLink link = new FakeLink();
        link.block = true;
        BridgeSender sender = BridgeSender.start(link, 8);
        sender.markReady();
        assertTrue(link.blocked.await(5, TimeUnit.SECONDS));

        for (int i = 1; i <= 50; i++) {
            assertTrue(sender.reportStatus(new StatusSnapshot(i, 100, 20, 1)));
        }
        assertEquals(0, sender.dropped(), "fifty reports use one queue slot");

        link.release.countDown();
        sender.close(WAIT);
        List<FakeLink.Call> reports = link.calls.stream().filter(call -> call.what().equals("status")).toList();
        assertEquals(1, reports.size());
        assertEquals(50, ((StatusSnapshot) reports.get(0).argument()).playersOnline());
    }

    @Test
    void statusStillGoesOutAfterTheQueueWasFull() throws InterruptedException {
        FakeLink link = new FakeLink();
        link.block = true;
        BridgeSender sender = BridgeSender.start(link, 1);
        sender.markReady();
        assertTrue(link.blocked.await(5, TimeUnit.SECONDS));
        assertTrue(sender.markReady());
        assertFalse(sender.reportStatus(new StatusSnapshot(1, 20, 20, 1)), "no slot left");

        link.block = false;
        link.release.countDown();
        // Counter-check: once there is room again, a later report is queued and sent.
        waitUntil(() -> link.names().size() >= 2);
        assertTrue(sender.reportStatus(new StatusSnapshot(2, 20, 20, 1)));
        sender.close(WAIT);
        assertEquals(List.of("ready", "ready", "status", "close"), link.names());
        assertEquals(2, ((StatusSnapshot) link.calls.get(2).argument()).playersOnline());
    }

    @Test
    void aFailingCallDoesNotStopTheSender() {
        FakeLink link = new FakeLink();
        link.fail = true;
        BridgeSender sender = BridgeSender.start(link);
        sender.markReady();
        waitUntil(() -> !link.calls.isEmpty());
        link.fail = false;
        sender.markReady();
        sender.close(WAIT);
        assertEquals(List.of("ready", "ready", "close"), link.names());
    }

    @Test
    void closeSendsWhatIsQueuedThenClosesTheLinkAndRefusesMore() {
        FakeLink link = new FakeLink();
        BridgeSender sender = BridgeSender.start(link);
        sender.markReady();
        sender.close(WAIT);
        sender.close(WAIT);

        assertTrue(sender.isClosed());
        assertFalse(sender.markReady());
        assertFalse(sender.reportStatus(new StatusSnapshot(0, 0, 20, 0)));
        assertEquals(List.of("ready", "close"), link.names());
        assertFalse(link.calls.get(1).thread().isAlive(), "the sender thread ends after closing the link");
    }

    @Test
    void closeWaitsOnlyAsLongAsAllowedForAStuckLink() throws InterruptedException {
        FakeLink link = new FakeLink();
        link.block = true;
        BridgeSender sender = BridgeSender.start(link, 2);
        sender.markReady();
        assertTrue(link.blocked.await(5, TimeUnit.SECONDS));
        sender.markReady();
        sender.markReady();

        long start = System.nanoTime();
        sender.close(Duration.ofMillis(200));
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(tookMillis < 2_000, "close took " + tookMillis + " ms");
        // The link is closed anyway, from the closing thread: that is what frees a hung socket write.
        assertTrue(link.names().contains("close"), "the stuck link was left open: " + link.names());
        link.release.countDown();
    }

    @Test
    void rejectsAnEmptyQueue() {
        FakeLink link = new FakeLink();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> BridgeSender.start(link, 0));
    }

    static void waitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within " + WAIT);
            }
            Thread.onSpinWait();
        }
    }
}
