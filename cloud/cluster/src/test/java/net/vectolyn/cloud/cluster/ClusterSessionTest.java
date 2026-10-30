package net.vectolyn.cloud.cluster;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.vectolyn.protocol.ChannelName;
import net.vectolyn.protocol.NodeId;
import net.vectolyn.protocol.message.ChannelMessage;
import net.vectolyn.protocol.message.Envelope;
import net.vectolyn.protocol.message.Ping;
import net.vectolyn.protocol.message.Request;
import net.vectolyn.protocol.message.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Requests, answers and pushes of a cluster session, with every way a peer can break them. */
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class ClusterSessionTest {

    private static final ChannelName ECHO = new ChannelName("test.echo");
    private static final ChannelName FAIL = new ChannelName("test.fail");
    private static final ChannelName CRASH = new ChannelName("test.crash");
    private static final ChannelName NEWS = new ChannelName("test.news");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static String code(CompletableFuture<byte[]> future) throws InterruptedException {
        try {
            future.get();
            throw new AssertionError("no failure");
        } catch (ExecutionException e) {
            return ((ClusterRpcException) e.getCause()).code();
        }
    }

    @Test
    void requestsGetAnswersAndErrorsGetOnlyTheirCode() throws Exception {
        List<String> news = new CopyOnWriteArrayList<>();
        CountDownLatch pushed = new CountDownLatch(1);
        ClusterHandlers head = ClusterHandlers.builder()
                .request(ECHO, (peer, payload) -> payload)
                .request(FAIL, (peer, payload) -> {
                    throw new ClusterRpcException(ClusterRpcException.NOT_FOUND);
                })
                .request(CRASH, (peer, payload) -> {
                    throw new IllegalStateException("secret detail that must not cross the wire");
                })
                .build();
        ClusterHandlers member = ClusterHandlers.builder()
                .push(NEWS, (peer, payload) -> {
                    news.add(peer + ":" + new String(payload, StandardCharsets.UTF_8));
                    pushed.countDown();
                })
                .build();
        try (SessionPair pair = new SessionPair(member, head)) {
            assertArrayEquals(new byte[] {1, 2, 3}, pair.member.call(ECHO, new byte[] {1, 2, 3}, TIMEOUT));
            assertEquals(ClusterRpcException.NOT_FOUND, code(pair.member.request(FAIL, new byte[0], TIMEOUT)));
            assertEquals(ClusterRpcException.FAILED, code(pair.member.request(CRASH, new byte[0], TIMEOUT)));
            assertTrue(pair.head.push(NEWS, "hi".getBytes(StandardCharsets.UTF_8)));
            assertTrue(pushed.await(10, TimeUnit.SECONDS));
            assertEquals(List.of("node-1:hi"), news, "the member's peer is the head");
            assertNull(pair.violation.get());
        }
    }

    @Test
    void anUnknownChannelBreaksTheProtocol() {
        try (SessionPair pair = new SessionPair(ClusterHandlers.NONE, ClusterHandlers.NONE)) {
            assertFalse(pair.head.receive(new Envelope(2, new Request(ECHO, new byte[0]))));
            assertFalse(pair.head.receive(Envelope.of(new ChannelMessage(NEWS, new byte[0]))));
            assertFalse(pair.head.receive(Envelope.of(new Ping(1))), "no other message type either");
        }
    }

    @Test
    void anAnswerToARequestNeverSentBreaksTheProtocol() {
        try (SessionPair pair = new SessionPair(ClusterHandlers.NONE, ClusterHandlers.NONE)) {
            assertFalse(pair.member.receive(new Envelope(7, new Response(Response.Status.OK, new byte[0]))));
        }
    }

    @Test
    void aRequestWithoutAnswerTimesOutAndItsLateAnswerIsDropped() throws Exception {
        List<Envelope> sent = new CopyOnWriteArrayList<>();
        ClusterSession session = new ClusterSession(new NodeId("node-1"), ClusterHandlers.NONE, e -> sent.add(e));
        try {
            CompletableFuture<byte[]> future = session.request(ECHO, new byte[0], Duration.ofMillis(100));
            assertEquals(ClusterRpcException.TIMEOUT,
                    assertThrows(ClusterRpcException.class, () -> session.call(ECHO, new byte[0], Duration.ofMillis(100)))
                            .code());
            assertThrows(ExecutionException.class, future::get);
            int id = sent.getFirst().requestId();
            assertTrue(id > 1, "ids start after the hello's");
            assertTrue(session.receive(new Envelope(id, new Response(Response.Status.OK, new byte[0]))),
                    "a late answer does not end the session");
            assertFalse(session.receive(new Envelope(id, new Response(Response.Status.OK, new byte[0]))),
                    "but a second one does");
        } finally {
            session.close();
        }
    }

    @Test
    void atMostSixteenRequestsAreOpen() throws Exception {
        ClusterSession session = new ClusterSession(new NodeId("node-1"), ClusterHandlers.NONE, e -> true);
        try {
            List<CompletableFuture<byte[]>> open = new ArrayList<>();
            for (int i = 0; i < ClusterSession.MAX_PENDING; i++) {
                open.add(session.request(ECHO, new byte[0], TIMEOUT));
            }
            assertEquals(ClusterRpcException.BUSY, code(session.request(ECHO, new byte[0], TIMEOUT)));
            session.close();
            for (CompletableFuture<byte[]> future : open) {
                assertEquals(ClusterRpcException.CLOSED, code(future), "the end of the session fails every open one");
            }
            assertEquals(ClusterRpcException.CLOSED, code(session.request(ECHO, new byte[0], TIMEOUT)));
        } finally {
            session.close();
        }
    }

    @Test
    void aCancelledRequestFreesItsSlotAndItsLateAnswerIsDropped() throws Exception {
        List<Envelope> sent = new CopyOnWriteArrayList<>();
        ClusterSession session = new ClusterSession(new NodeId("node-1"), ClusterHandlers.NONE, e -> sent.add(e));
        try {
            // A peer that never answers: cancelling must free the slots, or the session stays busy.
            for (int round = 0; round < 3; round++) {
                List<CompletableFuture<byte[]>> open = new ArrayList<>();
                for (int i = 0; i < ClusterSession.MAX_PENDING; i++) {
                    open.add(session.request(ECHO, new byte[0], TIMEOUT));
                }
                open.forEach(future -> future.cancel(false));
            }
            CompletableFuture<byte[]> next = session.request(ECHO, new byte[0], TIMEOUT);
            assertFalse(next.isDone(), "not busy after cancelled requests");
            assertTrue(session.receive(new Envelope(sent.getFirst().requestId(),
                    new Response(Response.Status.OK, new byte[0]))), "a late answer to a cancelled one is dropped");
            next.cancel(false);
        } finally {
            session.close();
        }
    }

    @Test
    void aFailedSendFailsTheRequest() throws Exception {
        ClusterSession session = new ClusterSession(new NodeId("node-1"), ClusterHandlers.NONE, e -> false);
        try {
            assertEquals(ClusterRpcException.CLOSED, code(session.request(ECHO, new byte[0], TIMEOUT)));
        } finally {
            session.close();
        }
    }

    @Test
    void aPeerThatFillsTheQueueBreaksTheProtocol() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ClusterHandlers slow = ClusterHandlers.builder().request(ECHO, (peer, payload) -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return payload;
        }).build();
        ClusterSession session = new ClusterSession(new NodeId("node-2"), slow, e -> true);
        try {
            boolean accepted = true;
            int sent = 0;
            // one runs on the worker, QUEUE wait, the next one is too many
            while (accepted && sent < ClusterSession.QUEUE + 10) {
                accepted = session.receive(new Envelope(sent + 2, new Request(ECHO, new byte[0])));
                sent++;
            }
            assertFalse(accepted);
            assertTrue(sent >= ClusterSession.QUEUE + 1, "an honest peer never gets there: " + sent);
        } finally {
            release.countDown();
            session.close();
        }
    }

    @Test
    void errorCodesFromTheNetworkAreChecked() {
        assertEquals("not-found", ClusterRpcException.code("not-found".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(ClusterRpcException.UNKNOWN, ClusterRpcException.code(new byte[0]));
        assertEquals(ClusterRpcException.UNKNOWN, ClusterRpcException.code("Exception: at x".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(ClusterRpcException.UNKNOWN, ClusterRpcException.code(new byte[33]));
        assertThrows(IllegalArgumentException.class, () -> new ClusterRpcException("No Spaces"));
    }

    @Test
    void handshakeChannelsAndDoubleHandlersAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> ClusterHandlers.builder().request(ClusterMessages.HELLO, (peer, payload) -> payload));
        assertThrows(IllegalArgumentException.class, () -> ClusterHandlers.builder()
                .request(ECHO, (peer, payload) -> payload).request(ECHO, (peer, payload) -> payload));
    }
}
