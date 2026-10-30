package net.vectolyn.cloud.cluster;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.vectolyn.protocol.ChannelName;
import net.vectolyn.protocol.NodeId;
import net.vectolyn.protocol.message.ChannelMessage;
import net.vectolyn.protocol.message.Envelope;
import net.vectolyn.protocol.message.Request;
import net.vectolyn.protocol.message.Response;

/**
 * Requests, answers and pushes on an established cluster session, the same on both ends (the
 * head's session with a member and the member's link to the head).
 *
 * <ul>
 *   <li>Outgoing requests get an id of their own (never 0, and never 1, the hello's), at most
 *       {@link #MAX_PENDING} are open at a time; one that gets no answer within its timeout fails
 *       with {@link ClusterRpcException#TIMEOUT}, and the end of the session fails every open one
 *       with {@link ClusterRpcException#CLOSED}.</li>
 *   <li>Incoming requests and pushes are handled off the reader thread, one after another on a
 *       worker of this session, with a queue of {@link #QUEUE} entries. A peer that fills it ends
 *       the session with {@code PROTOCOL_ERROR}: an honest peer has at most {@link #MAX_PENDING}
 *       requests open.</li>
 *   <li>An answer is {@code OK} with the handler's payload or {@code ERROR} with a short code
 *       ({@link ClusterRpcException}), never an exception text.</li>
 *   <li>A request or push on a channel without a handler, and an answer to a request that was
 *       never sent, end the session with {@code PROTOCOL_ERROR}. An answer that comes after its
 *       request timed out is dropped (the last {@link #TIMED_OUT_REMEMBERED} such ids are kept).</li>
 * </ul>
 */
public final class ClusterSession {

    private static final System.Logger LOG = System.getLogger(ClusterSession.class.getName());
    /** Outgoing requests open at a time. */
    public static final int MAX_PENDING = 16;
    /** Incoming requests and pushes waiting for the worker. */
    static final int QUEUE = 64;
    /** Ids of timed-out requests whose late answer is dropped instead of ending the session. */
    static final int TIMED_OUT_REMEMBERED = 64;
    /** First id of an outgoing request; 1 is the hello. */
    private static final int FIRST_ID = 2;

    /** Sends a frame on the session. */
    @FunctionalInterface
    interface Transport {

        /**
         * Sends a frame.
         *
         * @return false if it could not be sent; the transport ends the session then
         */
        boolean send(Envelope envelope);
    }

    private final NodeId peer;
    private final ClusterHandlers handlers;
    private final Transport transport;
    private final Object lock = new Object();
    /** Open requests by id; guarded by {@link #lock}. */
    private final Map<Integer, CompletableFuture<byte[]>> pending = new HashMap<>();
    /** Ids of timed-out requests, oldest first; guarded by {@link #lock}. */
    private final ArrayDeque<Integer> timedOutOrder = new ArrayDeque<>();
    private final Set<Integer> timedOut = new HashSet<>();
    private final ThreadPoolExecutor worker;
    private int nextId = FIRST_ID;
    private volatile boolean closed;

    /**
     * Creates the session's request layer.
     *
     * @param peer      the node at the other end
     * @param handlers  what this end answers
     * @param transport how frames are sent
     */
    ClusterSession(NodeId peer, ClusterHandlers handlers, Transport transport) {
        this.peer = Objects.requireNonNull(peer, "peer");
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE),
                Thread.ofVirtual().name("vectolyn-cluster-worker").factory());
    }

    /** The node at the other end. */
    public NodeId peer() {
        return peer;
    }

    /** Whether the session has ended. */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Sends a request.
     *
     * @param channel the channel; the peer must have a handler for it
     * @param payload at most 64 KiB
     * @param timeout how long to wait for the answer
     * @return the answer's payload, or a failure with a {@link ClusterRpcException}
     */
    public CompletableFuture<byte[]> request(ChannelName channel, byte[] payload, Duration timeout) {
        Request request = new Request(channel, payload);
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        int id;
        synchronized (lock) {
            if (closed) {
                return CompletableFuture.failedFuture(new ClusterRpcException(ClusterRpcException.CLOSED));
            }
            if (pending.size() >= MAX_PENDING) {
                return CompletableFuture.failedFuture(new ClusterRpcException(ClusterRpcException.BUSY));
            }
            id = allocateId();
            pending.put(id, future);
        }
        future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).whenComplete((value, error) -> {
            // Timed out or given up by the caller: the slot is free again, a late answer is dropped.
            if (error instanceof TimeoutException || error instanceof CancellationException) {
                synchronized (lock) {
                    if (pending.remove(id, future)) {
                        rememberTimedOut(id);
                    }
                }
            }
        });
        if (!transport.send(new Envelope(id, request))) {
            fail(id, future);
        }
        return future;
    }

    /**
     * Sends a request and waits for the answer.
     *
     * @return the answer's payload
     * @throws ClusterRpcException if the peer answered with an error, the request timed out, too
     *                             many are open or the session ended
     */
    public byte[] call(ChannelName channel, byte[] payload, Duration timeout) throws ClusterRpcException {
        try {
            return request(channel, payload, timeout).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ClusterRpcException rpc) {
                throw rpc;
            }
            throw new ClusterRpcException(e.getCause() instanceof TimeoutException ? ClusterRpcException.TIMEOUT
                    : ClusterRpcException.FAILED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClusterRpcException(ClusterRpcException.CLOSED);
        }
    }

    /**
     * Sends a push.
     *
     * @param channel the channel; the peer must have a handler for it
     * @param payload at most 64 KiB
     * @return whether it was sent
     */
    public boolean push(ChannelName channel, byte[] payload) {
        ChannelMessage message = new ChannelMessage(channel, payload);
        return !closed && transport.send(Envelope.of(message));
    }

    private void fail(int id, CompletableFuture<byte[]> future) {
        synchronized (lock) {
            pending.remove(id, future);
        }
        future.completeExceptionally(new ClusterRpcException(ClusterRpcException.CLOSED));
    }

    /** The next free id, wrapping before it turns negative; called with {@link #lock} held. */
    private int allocateId() {
        while (true) {
            int id = nextId;
            nextId = id == Integer.MAX_VALUE ? FIRST_ID : id + 1;
            if (!pending.containsKey(id) && !timedOut.contains(id)) {
                return id;
            }
        }
    }

    private void rememberTimedOut(int id) {
        timedOut.add(id);
        timedOutOrder.addLast(id);
        if (timedOutOrder.size() > TIMED_OUT_REMEMBERED) {
            timedOut.remove(timedOutOrder.removeFirst());
        }
    }

    /**
     * Takes a frame the reader got that is no ping, pong or disconnect. Runs on the reader thread.
     *
     * @return false if the frame breaks the protocol; the caller ends the session with
     *         {@code PROTOCOL_ERROR}
     */
    boolean receive(Envelope envelope) {
        return switch (envelope.message()) {
            case Response response -> answer(envelope.requestId(), response);
            case Request request -> {
                Optional<ClusterHandlers.RequestHandler> handler = handlers.request(request.channel());
                yield handler.isPresent()
                        && enqueue(() -> serve(envelope.requestId(), request.channel(), handler.get(), request.payload()));
            }
            case ChannelMessage message -> {
                Optional<ClusterHandlers.PushHandler> handler = handlers.push(message.channel());
                yield handler.isPresent() && enqueue(() -> take(message.channel(), handler.get(), message.payload()));
            }
            default -> false;
        };
    }

    private boolean answer(int id, Response response) {
        CompletableFuture<byte[]> future;
        synchronized (lock) {
            future = pending.remove(id);
            if (future == null) {
                // a late answer to a timed-out request is fine; one to a request never sent is not
                return timedOut.remove(id) || closed;
            }
        }
        if (response.status() == Response.Status.OK) {
            future.complete(response.payload());
        } else {
            future.completeExceptionally(new ClusterRpcException(ClusterRpcException.code(response.payload())));
        }
        return true;
    }

    private boolean enqueue(Runnable task) {
        try {
            worker.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            if (closed) {
                return true;
            }
            LOG.log(System.Logger.Level.WARNING, "Cluster: {0} sent more than {1} requests at once; session ended",
                    peer, QUEUE);
            return false;
        }
    }

    private void serve(int id, ChannelName channel, ClusterHandlers.RequestHandler handler, byte[] payload) {
        Response response;
        try {
            response = new Response(Response.Status.OK, handler.handle(peer, payload));
        } catch (ClusterRpcException e) {
            response = new Response(Response.Status.ERROR, e.payload());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Cluster: request on " + channel + " from " + peer + " failed", e);
            response = new Response(Response.Status.ERROR, new ClusterRpcException(ClusterRpcException.FAILED).payload());
        }
        if (!closed) {
            transport.send(new Envelope(id, response));
        }
    }

    private void take(ChannelName channel, ClusterHandlers.PushHandler handler, byte[] payload) {
        try {
            handler.handle(peer, payload);
        } catch (ClusterRpcException e) {
            LOG.log(System.Logger.Level.WARNING, "Cluster: push on {0} from {1} refused ({2})", channel, peer, e.code());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Cluster: push on " + channel + " from " + peer + " failed", e);
        }
    }

    /** Ends the request layer: every open request fails, the worker stops. Idempotent. */
    void close() {
        List<CompletableFuture<byte[]>> open;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            open = new ArrayList<>(pending.values());
            pending.clear();
        }
        worker.shutdownNow();
        for (CompletableFuture<byte[]> future : open) {
            future.completeExceptionally(new ClusterRpcException(ClusterRpcException.CLOSED));
        }
    }
}
