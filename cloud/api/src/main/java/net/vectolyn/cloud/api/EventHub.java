// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import net.vectolyn.cloud.api.event.ApiEvent;
import net.vectolyn.cloud.api.http.ApiJson;

/**
 * The open event streams ({@code /api/v1/events}), GraphQL WebSocket connections
 * ({@code /api/v1/graphql}) and gRPC {@code Events} calls, and the fan-out of {@link ApiEvent}s to them.
 *
 * <p>Each event is turned into JSON once and offered to every connection; the connection's
 * {@link Sink} filters and writes it. A stream whose socket buffer is full (a slow client) skips
 * events instead of piling them up in the node's memory; the next event that gets through is
 * preceded by {@code {"type":"dropped","data":{"count":n}}}. Output lines and log messages are
 * redacted once more before they are sent. Both kinds of connection count against one limit.
 * Thread-safe; {@link #publish} may be called from any thread.
 */
final class EventHub {

    /** Event types a client may filter on. */
    static final Set<String> TYPES = ApiEvent.TYPES.keySet();

    private final Map<Channel, Entry> streams = new ConcurrentHashMap<>();
    private final UnaryOperator<String> redactor;
    private final int max;

    EventHub(UnaryOperator<String> redactor, int max) {
        this.redactor = redactor;
        this.max = max;
    }

    /** The receiving end of one connection. */
    interface Sink {

        /**
         * Whether the connection receives an event type at the moment.
         *
         * @param type the type name
         * @return whether it wants it
         */
        boolean wants(String type);

        /**
         * Filters and writes one event. Called from the publishing thread.
         *
         * @param delivery the event, redacted, with its JSON envelope
         */
        void offer(Delivery delivery);

        /**
         * Ends the connection: a WebSocket close frame, or for gRPC the trailers with a status.
         *
         * @param channel the channel the sink was added with
         * @param status  1008 for a revoked or expired token, 1001 for shutdown
         * @param reason  short reason
         */
        // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
        @SuppressWarnings("FutureReturnValueIgnored")
        default void close(Channel channel, WebSocketCloseStatus status, String reason) {
            channel.writeAndFlush(new CloseWebSocketFrame(status, reason)).addListener(f -> channel.close());
        }
    }

    /**
     * One event on its way out.
     *
     * @param event the event, already redacted
     * @param json  {@code {"type":...,"time":...,"data":...}}
     */
    // The envelope is the encoded JSON; copying it into a List would cost every event, so the array stays.
    @SuppressWarnings("ArrayRecordComponent")
    record Delivery(ApiEvent event, byte[] json) {

        /**
         * The envelope as maps and lists (for GraphQL).
         *
         * @return the parsed envelope
         */
        @SuppressWarnings("unchecked")
        Map<String, Object> tree() {
            return ApiJson.mapper().readValue(json, Map.class);
        }
    }

    /**
     * Registers an event stream unless the limit is reached.
     *
     * @param types the event types the stream receives: what it asked for, cut down to what its
     *              token's scopes allow; never empty
     * @return false if there are already {@code max} connections
     */
    boolean add(Channel channel, String tokenId, Set<String> types, Set<String> services) {
        return add(channel, tokenId, new ChannelStream(channel, types, services));
    }

    /**
     * Registers a connection with its own sink unless the limit is reached. It is removed when the
     * channel closes.
     *
     * @return false if there are already {@code max} connections
     */
    synchronized boolean add(Channel channel, String tokenId, Sink sink) {
        if (streams.size() >= max) {
            return false;
        }
        streams.put(channel, new Entry(tokenId, sink));
        channel.closeFuture().addListener(f -> streams.remove(channel));
        return true;
    }

    /** Number of open connections. */
    int size() {
        return streams.size();
    }

    /**
     * Whether any open connection receives an event type, so a producer can skip work nobody reads.
     *
     * @param type the type name
     * @return whether a connection wants it
     */
    boolean anyWants(String type) {
        for (Entry entry : streams.values()) {
            if (entry.sink().wants(type)) {
                return true;
            }
        }
        return false;
    }

    // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
    @SuppressWarnings("FutureReturnValueIgnored")
    void publish(ApiEvent event) {
        if (streams.isEmpty()) {
            return;
        }
        ApiEvent safe = switch (event) {
            case ApiEvent.ServiceOutput output -> new ApiEvent.ServiceOutput(output.serviceId(),
                    redactor.apply(output.line()));
            case ApiEvent.NodeLog log -> new ApiEvent.NodeLog(log.level(), log.logger(), redactor.apply(log.message()));
            default -> event;
        };
        Delivery delivery = new Delivery(safe, envelope(safe.type(), safe));
        for (Map.Entry<Channel, Entry> entry : streams.entrySet()) {
            if (entry.getKey().isActive()) {
                entry.getValue().sink().offer(delivery);
            }
        }
    }

    /** Closes every connection of a revoked token (WebSocket close code 1008). */
    void closeToken(String tokenId) {
        streams.forEach((channel, entry) -> {
            if (entry.tokenId().equals(tokenId)) {
                entry.sink().close(channel, WebSocketCloseStatus.POLICY_VIOLATION, "token revoked");
            }
        });
    }

    /** Closes every connection whose token matches (WebSocket close code 1008), for expired tokens. */
    void closeTokensIf(Predicate<String> tokenIdMatches) {
        streams.forEach((channel, entry) -> {
            if (tokenIdMatches.test(entry.tokenId())) {
                entry.sink().close(channel, WebSocketCloseStatus.POLICY_VIOLATION, "token expired");
            }
        });
    }

    /** Closes every connection (1001, the node goes away). */
    void closeAll() {
        streams.forEach((channel, entry) -> entry.sink().close(channel, WebSocketCloseStatus.ENDPOINT_UNAVAILABLE,
                "shutting down"));
    }

    static byte[] envelope(String type, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("time", Instant.now().toString());
        body.put("data", data);
        return ApiJson.write(body);
    }

    /**
     * Whether a filter takes an event.
     *
     * @param types    the types it receives
     * @param services the services it receives, empty for all
     * @param event    the event
     * @return whether it takes it
     */
    static boolean matches(Set<String> types, Set<String> services, ApiEvent event) {
        if (!types.contains(event.type())) {
            return false;
        }
        if (event instanceof ApiEvent.Metrics || event instanceof ApiEvent.NodeLog) {
            return true; // node-wide: the services filter does not apply
        }
        return services.isEmpty() || (event.service() != null && services.contains(event.service()));
    }

    private record Entry(String tokenId, Sink sink) {
    }

    /** An event stream of {@code /api/v1/events}: the envelope as one text frame per event. */
    private static final class ChannelStream implements Sink {

        private final Channel channel;
        private final Set<String> types;
        private final Set<String> services;
        private final AtomicLong dropped = new AtomicLong();

        ChannelStream(Channel channel, Set<String> types, Set<String> services) {
            this.channel = channel;
            this.types = Set.copyOf(types);
            this.services = Set.copyOf(services);
        }

        @Override
        public boolean wants(String type) {
            return types.contains(type);
        }

        @Override
        // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
        @SuppressWarnings("FutureReturnValueIgnored")
        public void offer(Delivery delivery) {
            if (!matches(types, services, delivery.event())) {
                return;
            }
            if (!channel.isWritable()) {
                dropped.incrementAndGet();
                return;
            }
            long skipped = dropped.getAndSet(0);
            if (skipped > 0) {
                channel.write(new TextWebSocketFrame(Unpooled.wrappedBuffer(envelope("dropped", Map.of("count", skipped)))));
            }
            channel.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(delivery.json())));
        }
    }
}
