// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import graphql.ExecutionResult;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import net.vectolyn.cloud.api.auth.ApiToken;
import net.vectolyn.cloud.api.http.ApiException;
import net.vectolyn.cloud.api.http.ApiJson;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * One GraphQL WebSocket connection ({@code /api/v1/graphql}, subprotocol
 * {@code graphql-transport-ws}). The token was checked on the handshake, like for the event
 * stream; {@code connection_init} only starts the protocol.
 *
 * <p>Rules of the protocol that the server enforces: {@code connection_init} within
 * {@value #INIT_SECONDS} s (else 4408) and only once (4429); no {@code subscribe} before
 * {@code connection_ack} (4401); an operation id once at a time (4409); anything else that is not
 * a known message closes with 4400. On top: at most {@value #MAX_OPERATIONS} operations at once,
 * every {@code subscribe} takes one request from the token's rate limit and needs a token that is
 * still valid (else 4403).
 *
 * <p>The connection is one entry of the {@link EventHub}, so it counts against the WebSocket
 * limit and closes when its token is revoked or expires. Events go to each subscription whose
 * filter matches; a full socket buffer skips events and the next one that gets through is preceded
 * by an event of type {@code dropped}.
 */
final class GraphQlSocketHandler extends SimpleChannelInboundHandler<Object>
        implements EventHub.Sink {

    /** The subprotocol. */
    static final String PROTOCOL = "graphql-transport-ws";
    /** Seconds to wait for {@code connection_init}. */
    static final int INIT_SECONDS = 10;
    /** Most operations at once on one connection. */
    static final int MAX_OPERATIONS = 16;
    /** Largest message. */
    static final int MAX_MESSAGE = 64 * 1024;

    private static final Pattern ID = Pattern.compile("[\\x21-\\x7e]{1,64}");

    private final ApiServer server;
    private final WebSocketServerHandshaker handshaker;
    private final ApiToken token;
    private final Map<String, Operation> operations = new ConcurrentHashMap<>();
    private volatile @Nullable Channel channel;
    private boolean initialised;
    private volatile boolean acknowledged;
    private @Nullable ScheduledFuture<?> initTimeout;

    GraphQlSocketHandler(ApiServer server, WebSocketServerHandshaker handshaker, ApiToken token) {
        this.server = server;
        this.handshaker = handshaker;
        this.token = token;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        channel = ctx.channel();
        ScheduledFuture<?> timeout = ctx.executor().schedule(() -> {
            if (!initialised) {
                close(4408, "Connection initialisation timeout");
            }
        }, INIT_SECONDS, TimeUnit.SECONDS);
        initTimeout = timeout;
        ctx.channel().closeFuture().addListener(f -> {
            timeout.cancel(false);
            operations.values().forEach(Operation::cancel);
            operations.clear();
        });
    }

    // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof CloseWebSocketFrame close) {
            handshaker.close(ctx.channel(), close.retain());
        } else if (msg instanceof PingWebSocketFrame ping) {
            ctx.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
        } else if (msg instanceof TextWebSocketFrame text) {
            message(text.text());
        } else if (msg instanceof WebSocketFrame && !(msg instanceof PongWebSocketFrame)) {
            close(4400, "Binary and fragmented messages are not supported");
        }
        // a pong (the answer to our ping) and anything else (the end of the upgrade request) are dropped
    }

    private void message(String text) {
        Map<?, ?> message;
        try {
            message = ApiJson.mapper().readValue(text, Map.class);
        } catch (RuntimeException e) {
            close(4400, "Invalid message");
            return;
        }
        Object type = message.get("type");
        if (!(type instanceof String name)) {
            close(4400, "Invalid message");
            return;
        }
        switch (name) {
            case "connection_init" -> {
                if (initialised) {
                    close(4429, "Too many initialisation requests");
                    return;
                }
                initialised = true;
                ScheduledFuture<?> timeout = initTimeout;
                if (timeout != null) {
                    timeout.cancel(false);
                }
                acknowledged = true;
                send(Map.of("type", "connection_ack"));
            }
            case "ping" -> send(Map.of("type", "pong"));
            case "pong" -> {
                // answer to our ping, nothing to do
            }
            case "subscribe" -> subscribe(message);
            case "complete" -> {
                if (message.get("id") instanceof String id) {
                    Operation operation = operations.remove(id);
                    if (operation != null) {
                        operation.cancel();
                    }
                }
            }
            default -> close(4400, "Unknown message type");
        }
    }

    private void subscribe(Map<?, ?> message) {
        if (!acknowledged) {
            close(4401, "Unauthorized");
            return;
        }
        if (!(message.get("id") instanceof String id) || !ID.matcher(id).matches()) {
            close(4400, "Invalid message");
            return;
        }
        GraphQlApi.Body body;
        try {
            body = GraphQlApi.Body.of(message.get("payload"));
        } catch (ApiException e) {
            close(4400, "Invalid message");
            return;
        }
        if (!server.tokens().isValid(token.id())) {
            close(4403, "Forbidden");
            return;
        }
        Operation operation = new Operation(id);
        if (operations.putIfAbsent(id, operation) != null) {
            close(4409, "Subscriber for " + id + " already exists");
            return;
        }
        if (operations.size() > MAX_OPERATIONS) {
            fail(operation, "too-many-operations", "At most " + MAX_OPERATIONS + " operations at once.");
            return;
        }
        long wait = server.tokenLimiter().tryAcquire(token.id());
        if (wait > 0) {
            fail(operation, "rate-limited", "Too many requests. Try again in " + wait + " s.");
            return;
        }
        try {
            server.executor().execute(() -> run(operation, body));
        } catch (RejectedExecutionException e) {
            fail(operation, "shutting-down", "The node is shutting down.");
        }
    }

    private void run(Operation operation, GraphQlApi.Body body) {
        ExecutionResult result;
        try {
            result = server.graphQl().execute(token, body.query(), body.operationName(), body.variables(),
                    (caller, types, services) -> {
                        Events events = open(caller, types, services);
                        operation.events = events;
                        if (operation.cancelled) {
                            events.cancel();
                        }
                        return events;
                    });
        } catch (RuntimeException e) {
            fail(operation, "internal-error", "Internal error.");
            return;
        }
        if (result.getData() instanceof Publisher<?> publisher) {
            @SuppressWarnings("unchecked")
            Publisher<ExecutionResult> results = (Publisher<ExecutionResult>) publisher;
            results.subscribe(operation);
            return;
        }
        if (result.getData() == null && !result.getErrors().isEmpty()) {
            if (operations.remove(operation.id, operation)) {
                send(errorMessage(operation.id, result.toSpecification().get("errors")));
            }
            return;
        }
        if (operations.remove(operation.id, operation)) {
            send(next(operation.id, result));
            send(Map.of("id", operation.id, "type", "complete"));
        }
    }

    private void fail(Operation operation, String code, String text) {
        if (operations.remove(operation.id, operation)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("message", text);
            error.put("extensions", Map.of("code", code));
            send(errorMessage(operation.id, List.of(error)));
        }
    }

    private static Map<String, Object> errorMessage(String id, @Nullable Object errors) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id);
        message.put("type", "error");
        message.put("payload", errors);
        return message;
    }

    private static Map<String, Object> next(String id, ExecutionResult result) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id);
        message.put("type", "next");
        message.put("payload", result.toSpecification());
        return message;
    }

    // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
    @SuppressWarnings("FutureReturnValueIgnored")
    private void send(Map<String, ?> message) {
        Channel ch = channel;
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(ApiJson.write(message))));
        }
    }

    // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
    @SuppressWarnings("FutureReturnValueIgnored")
    private void close(int code, String reason) {
        Channel ch = channel;
        if (ch != null) {
            ch.writeAndFlush(new CloseWebSocketFrame(code, reason)).addListener(f -> ch.close());
        }
    }

    // ---------------------------------------------------------------- events

    private Events open(ApiToken caller, List<String> types, List<String> services) throws ApiException {
        Set<String> asked = new LinkedHashSet<>();
        for (String type : types) {
            if (!EventHub.TYPES.contains(type)) {
                throw GraphQlApi.bad("invalid-value", "\"types\" has an unknown event type.");
            }
            asked.add(type);
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String id : services) {
            if (!id.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
                throw GraphQlApi.bad("invalid-value", "\"services\" must list service ids.");
            }
            ids.add(id);
        }
        Set<String> permitted = ApiHttpHandler.permittedTypes(caller, asked);
        return new Events(permitted, ids);
    }

    @Override
    public boolean wants(String type) {
        for (Operation operation : operations.values()) {
            Events events = operation.events;
            if (events != null && events.types.contains(type)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void offer(EventHub.Delivery delivery) {
        Map<String, Object> tree = null;
        for (Operation operation : operations.values()) {
            Events events = operation.events;
            if (events == null || !EventHub.matches(events.types, events.services, delivery.event())) {
                continue;
            }
            if (tree == null) {
                tree = delivery.tree();
            }
            events.offer(tree);
        }
    }

    /** The events of one subscription; graphql-java subscribes to it and maps each to the selection. */
    private final class Events implements Publisher<Map<String, Object>>, Subscription {

        private final Set<String> types;
        private final Set<String> services;
        private @Nullable Subscriber<? super Map<String, Object>> subscriber;
        private long demand;
        private long dropped;
        private boolean cancelled;

        Events(Set<String> types, Set<String> services) {
            this.types = Set.copyOf(types);
            this.services = Set.copyOf(services);
        }

        @Override
        public void subscribe(Subscriber<? super Map<String, Object>> s) {
            synchronized (this) {
                if (subscriber != null) {
                    s.onSubscribe(this);
                    s.onError(new IllegalStateException("only one subscriber"));
                    return;
                }
                subscriber = s;
            }
            s.onSubscribe(this);
        }

        @Override
        public synchronized void request(long n) {
            if (n <= 0) {
                cancelled = true;
                return;
            }
            demand = Long.MAX_VALUE - demand < n ? Long.MAX_VALUE : demand + n;
        }

        @Override
        public synchronized void cancel() {
            cancelled = true;
        }

        synchronized void offer(Map<String, Object> event) {
            if (cancelled || subscriber == null) {
                return;
            }
            Channel ch = channel;
            if (demand == 0 || ch == null || !ch.isWritable()) {
                dropped++;
                return;
            }
            if (dropped > 0) {
                Map<String, Object> notice = new LinkedHashMap<>();
                notice.put("type", "dropped");
                notice.put("time", event.get("time"));
                notice.put("data", Map.of("count", dropped));
                dropped = 0;
                demand--;
                subscriber.onNext(notice);
                if (demand == 0) {
                    dropped++;
                    return;
                }
            }
            demand--;
            subscriber.onNext(event);
        }
    }

    /** One operation of the client; receives the results of a subscription. */
    private final class Operation implements Subscriber<ExecutionResult> {

        private final String id;
        private volatile @Nullable Subscription subscription;
        private volatile @Nullable Events events;
        private volatile boolean cancelled;

        Operation(String id) {
            this.id = id;
        }

        @Override
        public void onSubscribe(Subscription s) {
            subscription = s;
            if (cancelled) {
                s.cancel();
                return;
            }
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ExecutionResult result) {
            if (!cancelled) {
                send(next(id, result));
            }
        }

        @Override
        public void onError(Throwable t) {
            if (operations.remove(id, this)) {
                send(errorMessage(id, List.of(Map.of("message", "The subscription failed."))));
            }
        }

        @Override
        public void onComplete() {
            if (operations.remove(id, this)) {
                send(Map.of("id", id, "type", "complete"));
            }
        }

        void cancel() {
            cancelled = true;
            Subscription s = subscription;
            if (s != null) {
                s.cancel();
            }
            Events e = events;
            if (e != null) {
                e.cancel();
            }
        }
    }

    // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent idle) {
            if (idle.state() == IdleState.WRITER_IDLE) {
                ctx.writeAndFlush(new PingWebSocketFrame());
            } else if (idle.state() == IdleState.READER_IDLE) {
                close(1000, "idle");
            }
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
