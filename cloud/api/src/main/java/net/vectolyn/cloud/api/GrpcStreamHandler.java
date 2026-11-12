// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2StreamFrame;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.vectolyn.cloud.api.auth.ApiToken;
import net.vectolyn.cloud.api.grpc.Proto;
import net.vectolyn.cloud.api.grpc.ProtoSchema;
import net.vectolyn.cloud.api.http.ApiException;
import org.jspecify.annotations.Nullable;

/**
 * One gRPC call: one HTTP/2 stream of the gRPC listener ({@code api.grpc-port}).
 *
 * <p>Checks in this order, all before the request message is read: the rate limit of the client
 * address, method {@code POST} (else HTTP 405), content type {@code application/grpc} or
 * {@code application/grpc+proto} (else HTTP 415), a known method (else {@code UNIMPLEMENTED}), too
 * many wrong tokens, the token from {@code authorization: Bearer} ({@code UNAUTHENTICATED}) and the
 * rate limit of the token ({@code RESOURCE_EXHAUSTED}). The request is exactly one uncompressed
 * message of at most {@code maxBodyBytes}, it shares the body budget of its connection
 * ({@link GrpcConnection}) and must be complete within the idle timeout. Unary methods run on a
 * virtual thread like REST requests; {@code Events} stays open and is an entry of the
 * {@link EventHub}.
 *
 * <p>Every failure is a gRPC status in the trailers with {@code grpc-message} and the REST code in
 * {@code vectolyn-code}; nothing of the input is echoed. A refused stream is then reset, so the
 * client cannot keep it open or keep sending data on it.
 */
final class GrpcStreamHandler extends SimpleChannelInboundHandler<Http2StreamFrame> implements EventHub.Sink {

    private static final System.Logger LOG = System.getLogger(GrpcStreamHandler.class.getName());
    private static final String BEARER = "bearer ";
    private static final String EVENTS = "Events";

    private final ApiServer server;
    private final GrpcService service;
    private ProtoSchema.@Nullable Method method;
    private @Nullable ApiToken token;
    private @Nullable ByteArrayOutputStream body;
    // Read by the deadline task and the close listener as well as by the stream handler.
    private volatile boolean finished;
    private volatile @Nullable Channel channel;
    private final AtomicBoolean ended = new AtomicBoolean();
    private Set<String> types = Set.of();
    private Set<String> services = Set.of();
    private final AtomicLong dropped = new AtomicLong();
    private @Nullable GrpcConnection connection;
    private final AtomicLong reserved = new AtomicLong();
    private java.util.concurrent.@Nullable ScheduledFuture<?> deadline;

    GrpcStreamHandler(ApiServer server, GrpcService service) {
        this.server = server;
        this.service = service;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        channel = ctx.channel();
    }

    // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    protected void channelRead0(ChannelHandlerContext ctx, Http2StreamFrame frame) {
        if (finished) {
            return;
        }
        if (frame instanceof Http2ResetFrame) {
            ctx.close();
            return;
        }
        switch (frame) {
            case Http2HeadersFrame headers -> {
                if (body == null) {
                    head(ctx, headers.headers());
                }
                if (!finished && headers.isEndStream()) {
                    complete(ctx);
                }
            }
            case Http2DataFrame data -> {
                ByteBuf content = data.content();
                if (body == null) {
                    return;
                }
                if (body.size() + content.readableBytes() > server.limits().maxBodyBytes() + 5) {
                    fail(ctx, new ApiException(413, "body-too-large",
                            "The message is larger than " + server.limits().maxBodyBytes() + " bytes."));
                    return;
                }
                if (connection != null) {
                    if (!connection.reserve(content.readableBytes())) {
                        fail(ctx, new ApiException(503, "busy",
                                "Too many unfinished requests on this connection. Try again shortly."));
                        return;
                    }
                    reserved.addAndGet(content.readableBytes());
                }
                byte[] chunk = new byte[content.readableBytes()];
                content.readBytes(chunk);
                body.writeBytes(chunk);
                if (data.isEndStream()) {
                    complete(ctx);
                }
            }
            default -> {
                // window updates and the like: nothing to do
            }
        }
    }

    private void head(ChannelHandlerContext ctx, Http2Headers headers) {
        Channel parent = ctx.channel().parent();
        String client = ClientAddress.key(parent == null ? null : parent.remoteAddress());
        long wait = server.addressLimiter().tryAcquire(client);
        if (wait > 0) {
            fail(ctx, ApiException.tooManyRequests(wait));
            return;
        }
        if (!"POST".contentEquals(headers.method() == null ? "" : headers.method())) {
            httpStatus(ctx, "405");
            return;
        }
        if (!isGrpcProto(headers.get("content-type"))) {
            httpStatus(ctx, "415");
            return;
        }
        String path = headers.path() == null ? "" : headers.path().toString();
        ProtoSchema.Method found = service.method(path);
        if (found == null) {
            trailersOnly(ctx, GrpcStatus.UNIMPLEMENTED, "Unknown method.", "not-found");
            return;
        }
        long failures = server.authFailureLimiter().peek(client);
        if (failures > 0) {
            fail(ctx, ApiException.tooManyRequests(failures));
            return;
        }
        CharSequence authorization = headers.get("authorization");
        String presented = authorization == null ? null : authorization.toString();
        if (presented == null || presented.length() <= BEARER.length()
                || !presented.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            if (presented != null) {
                server.authFailureLimiter().tryAcquire(client);
            }
            fail(ctx, unauthorized());
            return;
        }
        Optional<ApiToken> authenticated = server.tokens().authenticate(presented.substring(BEARER.length()).strip());
        if (authenticated.isEmpty()) {
            server.authFailureLimiter().tryAcquire(client);
            fail(ctx, unauthorized());
            return;
        }
        wait = server.tokenLimiter().tryAcquire(authenticated.get().id());
        if (wait > 0) {
            fail(ctx, ApiException.tooManyRequests(wait));
            return;
        }
        method = found;
        token = authenticated.get();
        body = new ByteArrayOutputStream();
        GrpcConnection owner = GrpcConnection.of(ctx.channel());
        if (owner != null) {
            connection = owner;
            owner.begin();
            ctx.channel().closeFuture().addListener(f -> {
                releaseBody();
                owner.end();
            });
        }
        deadline = ctx.executor().schedule(() -> {
            if (body != null && !finished) {
                fail(ctx, new ApiException(408, "request-timeout", "The request message did not arrive in time."));
            }
        }, server.limits().idleTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
    }

    /** {@code application/grpc} or {@code application/grpc+proto}, parameters after {@code ;} allowed. */
    static boolean isGrpcProto(@Nullable CharSequence contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.toString();
        int semicolon = type.indexOf(';');
        String base = (semicolon < 0 ? type : type.substring(0, semicolon)).strip().toLowerCase(java.util.Locale.ROOT);
        return base.equals("application/grpc") || base.equals("application/grpc+proto");
    }

    /** Gives the body bytes back to the connection and stops the deadline; safe to call twice. */
    private void releaseBody() {
        long held = reserved.getAndSet(0);
        if (connection != null && held > 0) {
            connection.release(held);
        }
        if (deadline != null) {
            deadline.cancel(false);
            deadline = null;
        }
    }

    // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
    @SuppressWarnings("FutureReturnValueIgnored")
    private void complete(ChannelHandlerContext ctx) {
        ByteArrayOutputStream pending = body;
        ProtoSchema.Method m = method;
        ApiToken caller = token;
        if (pending == null || m == null || caller == null || finished) {
            return;
        }
        Map<String, Object> request;
        byte[] received = pending.toByteArray();
        body = null;
        releaseBody();
        try {
            request = Proto.decode(m.request(), GrpcStatus.unframe(received));
        } catch (GrpcStatus.FramingException e) {
            trailersOnly(ctx, e.status(), e.getMessage(), "invalid-message");
            return;
        } catch (Proto.ProtoException e) {
            trailersOnly(ctx, GrpcStatus.INTERNAL, "The request message cannot be read: " + e.getMessage() + ".",
                    "invalid-message");
            return;
        }
        if (m.name().equals(EVENTS)) {
            events(ctx, caller, request);
            return;
        }
        GrpcCalls.Call call = service.call(m.name());
        if (call == null) {
            trailersOnly(ctx, GrpcStatus.UNIMPLEMENTED, "Unknown method.", "not-found");
            return;
        }
        if (!server.requestSlots().tryAcquire()) {
            fail(ctx, new ApiException(503, "busy", "The API is busy. Try again shortly."));
            return;
        }
        finished = true;
        try {
            server.executor().execute(() -> {
                try {
                    Map<String, Object> answer = call.run(caller, request);
                    byte[] message = Proto.encode(m.response(), answer);
                    onEventLoop(ctx, () -> {
                        ctx.write(new DefaultHttp2HeadersFrame(responseHeaders()));
                        ctx.write(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(GrpcStatus.frame(message))));
                        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(GrpcStatus.trailers(GrpcStatus.OK, null, null), true));
                    });
                } catch (Exception e) {
                    ApiException error = e instanceof ApiException api ? api : internal(e);
                    onEventLoop(ctx, () -> sendError(ctx, error));
                } finally {
                    server.requestSlots().release();
                }
            });
        } catch (RejectedExecutionException e) {
            server.requestSlots().release();
            sendError(ctx, ApiException.shuttingDown());
        }
    }

    /** Runs on the event loop; after shutdown the answer has nowhere to go and is dropped. */
    private static void onEventLoop(ChannelHandlerContext ctx, Runnable task) {
        try {
            ctx.channel().eventLoop().execute(task);
        } catch (RejectedExecutionException e) {
            LOG.log(System.Logger.Level.DEBUG, "gRPC answer dropped: the server is stopping");
        }
    }

    // ---------------------------------------------------------------- events

    // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
    @SuppressWarnings("FutureReturnValueIgnored")
    private void events(ChannelHandlerContext ctx, ApiToken caller, Map<String, Object> request) {
        try {
            Set<String> asked = new LinkedHashSet<>();
            for (Object type : list(request.get("types"))) {
                if (!(type instanceof String name) || !EventHub.TYPES.contains(name)) {
                    throw new ApiException(400, "invalid-value", "\"types\" has an unknown event type.");
                }
                asked.add(name);
            }
            Set<String> ids = new LinkedHashSet<>();
            for (Object id : list(request.get("services"))) {
                ids.add(RouteCaller.segment(id, "services"));
            }
            types = ApiHttpHandler.permittedTypes(caller, asked);
            services = Set.copyOf(ids);
        } catch (ApiException e) {
            fail(ctx, e);
            return;
        }
        finished = true;
        if (!server.events().add(ctx.channel(), caller.id(), this)) {
            sendError(ctx, new ApiException(503, "too-many-streams", "Too many event streams are open."));
            return;
        }
        if (!server.tokens().isValid(caller.id())) {
            sendError(ctx, unauthorized());
            return;
        }
        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(responseHeaders()));
    }

    private static List<?> list(@Nullable Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    @Override
    public boolean wants(String type) {
        return types.contains(type);
    }

    // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void offer(EventHub.Delivery delivery) {
        Channel ch = channel;
        if (ended.get() || ch == null || !EventHub.matches(types, services, delivery.event())) {
            return;
        }
        if (!ch.isWritable()) {
            dropped.incrementAndGet();
            return;
        }
        Map<String, Object> tree = delivery.tree();
        long skipped = dropped.getAndSet(0);
        if (skipped > 0) {
            ch.write(event("dropped", String.valueOf(tree.get("time")), Map.of("count", skipped)));
        }
        ch.writeAndFlush(event(String.valueOf(tree.get("type")), String.valueOf(tree.get("time")), tree.get("data")));
    }

    private DefaultHttp2DataFrame event(String type, String time, @Nullable Object data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("time", time);
        event.put("json", GrpcCalls.write(data));
        return new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(GrpcStatus.frame(
                Proto.encode(Objects.requireNonNull(service.schema().methods().get(EVENTS), EVENTS).response(), event))));
    }

    // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void close(Channel ch, WebSocketCloseStatus status, String reason) {
        int code = status.code() == WebSocketCloseStatus.POLICY_VIOLATION.code() ? GrpcStatus.UNAUTHENTICATED
                : GrpcStatus.UNAVAILABLE;
        if (ended.compareAndSet(false, true)) {
            ch.writeAndFlush(new DefaultHttp2HeadersFrame(GrpcStatus.trailers(code, "The stream ended: " + reason + ".",
                    null), true)).addListener(f -> ch.close());
        }
    }

    // ---------------------------------------------------------------- answers

    private static Http2Headers responseHeaders() {
        return new DefaultHttp2Headers().status("200").set("content-type", "application/grpc")
                .set("grpc-accept-encoding", "identity");
    }

    private void fail(ChannelHandlerContext ctx, ApiException error) {
        finished = true;
        body = null;
        releaseBody();
        sendError(ctx, error);
    }

    private void sendError(ChannelHandlerContext ctx, ApiException error) {
        trailersOnly(ctx, GrpcStatus.of(error.status()), error.getMessage(), error.code());
    }

    // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
    @SuppressWarnings("FutureReturnValueIgnored")
    private void trailersOnly(ChannelHandlerContext ctx, int status, @Nullable String message, String code) {
        finished = true;
        body = null;
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        Http2Headers headers = GrpcStatus.trailers(status, message, code).status("200")
                .set("content-type", "application/grpc");
        // Closing the stream channel resets the stream if the client has not finished sending.
        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true)).addListener(f -> ctx.channel().close());
    }

    // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
    @SuppressWarnings("FutureReturnValueIgnored")
    private void httpStatus(ChannelHandlerContext ctx, String status) {
        finished = true;
        body = null;
        ended.set(true);
        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status(status), true))
                .addListener(f -> ctx.channel().close());
    }

    private static ApiException unauthorized() {
        return new ApiException(401, "unauthorized", "A valid API token is required (authorization: Bearer vct_...).");
    }

    private ApiException internal(Exception e) {
        LOG.log(System.Logger.Level.ERROR, "gRPC call failed", e);
        return new ApiException(500, "internal-error", "Internal error.");
    }

    // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.log(System.Logger.Level.DEBUG, "gRPC stream failed: {0}", cause.getClass().getSimpleName());
        ctx.close();
    }
}
