package net.vectora.cloud.network;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.vectora.protocol.ProtocolVersion;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.Disconnect;
import net.vectora.protocol.message.Envelope;
import net.vectora.protocol.message.Request;
import net.vectora.protocol.message.Response;

/**
 * One authenticated connection of a service: its channel and the requests open on it in both
 * directions.
 */
final class ServiceSession {

    private final ServiceId service;
    private final Channel channel;
    private final ProtocolVersion version;
    private final int maxPendingRequests;
    private final Map<Integer, CompletableFuture<Response>> outgoing = new ConcurrentHashMap<>();
    private final AtomicInteger nextRequestId = new AtomicInteger();
    private final AtomicInteger incoming = new AtomicInteger();
    private volatile boolean replaced;
    private volatile Disconnect.Reason closedByCloud;

    ServiceSession(ServiceId service, Channel channel, ProtocolVersion version, int maxPendingRequests) {
        this.service = service;
        this.channel = channel;
        this.version = version;
        this.maxPendingRequests = maxPendingRequests;
    }

    ServiceId service() {
        return service;
    }

    /** The version the connection uses: the lower one of the service and the cloud. */
    ProtocolVersion version() {
        return version;
    }

    Channel channel() {
        return channel;
    }

    /** Returns the peer's address, or {@code null} for a channel without one (embedded tests). */
    InetSocketAddress remoteAddress() {
        return channel.remoteAddress() instanceof InetSocketAddress address ? address : null;
    }

    boolean isReplaced() {
        return replaced;
    }

    void markReplaced() {
        replaced = true;
    }

    /** Returns the reason the cloud closed this connection with, or {@code null}. */
    Disconnect.Reason closedByCloud() {
        return closedByCloud;
    }

    /**
     * Sends {@code Disconnect} with {@code reason} and closes the connection once it is written.
     * Reading stops at once; a peer that does not take the message within a second is cut anyway.
     */
    ChannelFuture disconnect(Disconnect.Reason reason) {
        closedByCloud = reason;
        channel.config().setAutoRead(false);
        if (!channel.eventLoop().isShuttingDown()) {
            channel.eventLoop().schedule(() -> {
                channel.close();
            }, ConnectionHandler.CLOSE_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        }
        return channel.writeAndFlush(Envelope.of(new Disconnect(reason))).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Sends a request and returns the future of its response. It fails with
     * {@link java.util.concurrent.TimeoutException} after {@code timeout}, with {@link ServiceNotConnectedException} if
     * the connection closes first, and with {@link IllegalStateException} right away if too many
     * requests are open.
     */
    CompletableFuture<Response> request(Request request, Duration timeout) {
        CompletableFuture<Response> future = new CompletableFuture<>();
        if (!channel.isActive()) {
            future.completeExceptionally(new ServiceNotConnectedException(service + " is not connected"));
            return future;
        }
        if (outgoing.size() >= maxPendingRequests) {
            future.completeExceptionally(new IllegalStateException("too many open requests to " + service));
            return future;
        }
        int id = reserveRequestId();
        // Registered before the timer starts, so a timeout can never miss its entry.
        outgoing.put(id, future);
        future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((response, error) -> outgoing.remove(id, future));
        channel.writeAndFlush(new Envelope(id, request)).addListener(write -> {
            if (!write.isSuccess()) {
                fail(id, new ServiceNotConnectedException("request to " + service + " could not be sent"));
            }
        });
        return future;
    }

    /** Completes the request with this id; a late or unknown response is dropped. */
    void complete(int requestId, Response response) {
        CompletableFuture<Response> future = outgoing.remove(requestId);
        if (future != null) {
            future.complete(response);
        }
    }

    /** Fails every open request, used when the connection has closed. */
    void failAll() {
        List<Integer> ids = new ArrayList<>(outgoing.keySet());
        for (int id : ids) {
            fail(id, new ServiceNotConnectedException("connection to " + service + " closed"));
        }
    }

    /** Counts a request from the service; {@code false} if too many are open already. */
    boolean tryStartIncoming() {
        if (incoming.incrementAndGet() > maxPendingRequests) {
            incoming.decrementAndGet();
            return false;
        }
        return true;
    }

    /** Marks a request from the service as answered. */
    void endIncoming() {
        incoming.decrementAndGet();
    }

    private void fail(int id, RuntimeException cause) {
        CompletableFuture<Response> future = outgoing.remove(id);
        if (future != null) {
            future.completeExceptionally(cause);
        }
    }

    /** A counter that skips 0 and wraps to 1, and skips ids that are still open. */
    private int reserveRequestId() {
        while (true) {
            int id = nextRequestId.incrementAndGet() & Integer.MAX_VALUE;
            if (id != Envelope.NO_REQUEST && !outgoing.containsKey(id)) {
                return id;
            }
        }
    }
}
