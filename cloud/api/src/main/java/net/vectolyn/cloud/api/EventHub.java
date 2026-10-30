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
import java.util.function.UnaryOperator;
import net.vectolyn.cloud.api.event.ApiEvent;
import net.vectolyn.cloud.api.http.ApiJson;

/**
 * The open event streams ({@code /api/v1/events}) and the fan-out of {@link ApiEvent}s to them.
 *
 * <p>Each event is turned into JSON once and written to every stream whose filter matches. A
 * stream whose socket buffer is full (a slow client) skips events instead of piling them up in the
 * node's memory; the next event that gets through is preceded by
 * {@code {"type":"dropped","data":{"count":n}}}. Output lines are redacted once more before they
 * are sent. Thread-safe; {@link #publish} may be called from any thread.
 */
final class EventHub {

    /** Event types a client may filter on. */
    static final Set<String> TYPES = Set.of("service-state", "service-output", "player");

    private final Map<Channel, Stream> streams = new ConcurrentHashMap<>();
    private final UnaryOperator<String> redactor;
    private final int max;

    EventHub(UnaryOperator<String> redactor, int max) {
        this.redactor = redactor;
        this.max = max;
    }

    /**
     * Registers a stream unless the limit is reached.
     *
     * @return false if there are already {@code max} streams
     */
    synchronized boolean add(Channel channel, String tokenId, Set<String> types, Set<String> services) {
        if (streams.size() >= max) {
            return false;
        }
        streams.put(channel, new Stream(tokenId, types, services));
        channel.closeFuture().addListener(f -> streams.remove(channel));
        return true;
    }

    /** Number of open streams. */
    int size() {
        return streams.size();
    }

    void publish(ApiEvent event) {
        if (streams.isEmpty()) {
            return;
        }
        ApiEvent safe = event instanceof ApiEvent.ServiceOutput output
                ? new ApiEvent.ServiceOutput(output.serviceId(), redactor.apply(output.line()))
                : event;
        byte[] json = envelope(safe.type(), safe);
        for (Map.Entry<Channel, Stream> entry : streams.entrySet()) {
            Stream stream = entry.getValue();
            if (!stream.wants(safe)) {
                continue;
            }
            Channel channel = entry.getKey();
            if (!channel.isActive()) {
                continue;
            }
            if (!channel.isWritable()) {
                stream.dropped.incrementAndGet();
                continue;
            }
            long dropped = stream.dropped.getAndSet(0);
            if (dropped > 0) {
                channel.write(new TextWebSocketFrame(Unpooled.wrappedBuffer(envelope("dropped", Map.of("count", dropped)))));
            }
            channel.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(json)));
        }
    }

    /** Closes every stream of a revoked token (WebSocket close code 1008). */
    void closeToken(String tokenId) {
        streams.forEach((channel, stream) -> {
            if (stream.tokenId.equals(tokenId)) {
                close(channel, WebSocketCloseStatus.POLICY_VIOLATION, "token revoked");
            }
        });
    }

    /** Closes every stream (1001, the node goes away). */
    void closeAll() {
        streams.keySet().forEach(channel -> close(channel, WebSocketCloseStatus.ENDPOINT_UNAVAILABLE, "shutting down"));
    }

    private static void close(Channel channel, WebSocketCloseStatus status, String reason) {
        channel.writeAndFlush(new CloseWebSocketFrame(status, reason)).addListener(f -> channel.close());
    }

    static byte[] envelope(String type, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("time", Instant.now().toString());
        body.put("data", data);
        return ApiJson.write(body);
    }

    private static final class Stream {

        private final String tokenId;
        private final Set<String> types;
        private final Set<String> services;
        private final AtomicLong dropped = new AtomicLong();

        Stream(String tokenId, Set<String> types, Set<String> services) {
            this.tokenId = tokenId;
            this.types = Set.copyOf(types);
            this.services = Set.copyOf(services);
        }

        boolean wants(ApiEvent event) {
            if (!types.isEmpty() && !types.contains(event.type())) {
                return false;
            }
            return services.isEmpty() || (event.service() != null && services.contains(event.service()));
        }
    }
}
