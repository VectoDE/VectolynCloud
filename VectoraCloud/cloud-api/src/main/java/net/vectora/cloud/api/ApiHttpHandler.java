package net.vectora.cloud.api;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.ReferenceCountUtil;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import net.vectora.cloud.api.auth.ApiToken;
import net.vectora.cloud.api.auth.Scope;
import net.vectora.cloud.api.backend.ApiBackendException;
import net.vectora.cloud.api.http.ApiException;
import net.vectora.cloud.api.http.ApiJson;
import net.vectora.cloud.api.http.ApiRequest;
import net.vectora.cloud.api.http.ApiResponse;
import net.vectora.cloud.api.http.ApiRoutes;
import net.vectora.cloud.api.http.Router;
import net.vectora.cloud.config.error.ConfigException;

/**
 * One HTTP connection of the API. Order of the checks for every request, all before the body is
 * read:
 * <ol>
 *   <li>rate limit of the client address (429);</li>
 *   <li>CORS preflight from an allowed origin (204, the only answer without a token);</li>
 *   <li>too many wrong tokens from this address (429), then the token (401);</li>
 *   <li>rate limit of the token (429);</li>
 *   <li>routing (400, 404, 405), then the scope (403);</li>
 *   <li>body size (413) and content type (415).</li>
 * </ol>
 * The handler then runs on a virtual thread, so a slow backend call (a stop waits for the process)
 * never blocks a Netty event loop. One request at a time per connection; a pipelined second request
 * closes the connection after the first answer.
 */
final class ApiHttpHandler extends SimpleChannelInboundHandler<HttpObject> {

    private static final System.Logger LOG = System.getLogger(ApiHttpHandler.class.getName());
    private static final String BEARER = "bearer ";
    private static final String WS_PROTOCOL = "vectora.v1";
    private static final String WS_BEARER = "vectora.bearer.";
    private static final int WS_MAX_FRAME = 4096;

    private final ApiServer server;

    private Pending pending;
    private boolean busy;
    private boolean closeAfterResponse;

    ApiHttpHandler(ApiServer server) {
        super(false);
        this.server = server;
    }

    /** A request whose head passed every check and whose body is being collected. */
    private record Pending(HttpRequest head, Router.Resolution.Found<ApiRoutes.Handler> route, ApiToken token,
                           String origin, boolean keepAlive, boolean upgrade, ByteArrayOutputStream body) {
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
        try {
            if (msg instanceof HttpRequest request) {
                if (busy || pending != null) {
                    closeAfterResponse = true; // pipelining is not supported: answer the first, then close
                    return;
                }
                head(ctx, request);
            }
            if (msg instanceof HttpContent content && pending != null && !busy) {
                ByteBuf data = content.content();
                if (pending.body().size() + data.readableBytes() > server.limits().maxBodyBytes()) {
                    pending = null;
                    reject(ctx, null, new ApiException(413, "body-too-large",
                            "The body is larger than " + server.limits().maxBodyBytes() + " bytes."), true);
                    return;
                }
                data.readBytes(pending.body(), data.readableBytes());
                if (content instanceof LastHttpContent) {
                    Pending complete = pending;
                    pending = null;
                    if (complete.upgrade()) {
                        upgrade(ctx, complete);
                    } else {
                        dispatch(ctx, complete);
                    }
                }
            }
        } catch (IOException e) {
            ctx.close();
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private void head(ChannelHandlerContext ctx, HttpRequest request) throws IOException {
        if (!request.decoderResult().isSuccess()) {
            reject(ctx, null, new ApiException(400, "bad-request", "The request could not be read."), true);
            return;
        }
        String client = ClientAddress.key(ctx.channel().remoteAddress());
        long wait = server.addressLimiter().tryAcquire(client);
        if (wait > 0) {
            reject(ctx, null, ApiException.tooManyRequests(wait), true);
            return;
        }
        HttpHeaders headers = request.headers();
        String origin = headers.get(HttpHeaderNames.ORIGIN);
        boolean allowedOrigin = origin != null && server.settings().corsOrigins().contains(origin);
        String method = request.method().name();
        if (method.equals("OPTIONS") && allowedOrigin && headers.contains(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD)) {
            preflight(ctx, origin, HttpUtil.isKeepAlive(request));
            return;
        }
        String responseOrigin = allowedOrigin ? origin : null;
        boolean upgrade = headers.containsValue(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET, true);

        long failures = server.authFailureLimiter().peek(client);
        if (failures > 0) {
            reject(ctx, responseOrigin, ApiException.tooManyRequests(failures), true);
            return;
        }
        Optional<String> presented = presentedToken(headers, upgrade);
        if (presented.isEmpty()) {
            reject(ctx, responseOrigin, unauthorized(), true);
            return;
        }
        Optional<ApiToken> token = server.tokens().authenticate(presented.get());
        if (token.isEmpty()) {
            server.authFailureLimiter().tryAcquire(client);
            reject(ctx, responseOrigin, unauthorized(), true);
            return;
        }
        wait = server.tokenLimiter().tryAcquire(token.get().id());
        if (wait > 0) {
            reject(ctx, responseOrigin, ApiException.tooManyRequests(wait), true);
            return;
        }
        Router.Resolution<ApiRoutes.Handler> resolution = server.router().resolve(method, request.uri());
        Router.Resolution.Found<ApiRoutes.Handler> found;
        switch (resolution) {
            case Router.Resolution.Found<ApiRoutes.Handler> f -> found = f;
            case Router.Resolution.NotFound<ApiRoutes.Handler>() -> {
                reject(ctx, responseOrigin, new ApiException(404, "not-found", "No such endpoint."), true);
                return;
            }
            case Router.Resolution.MethodNotAllowed<ApiRoutes.Handler> m -> {
                reject(ctx, responseOrigin, new ApiException(405, "method-not-allowed",
                        "Allowed: " + String.join(", ", new TreeSet<>(m.allowed())) + "."), true);
                return;
            }
            case Router.Resolution.Invalid<ApiRoutes.Handler> i -> {
                reject(ctx, responseOrigin, new ApiException(400, i.code(), "The request target breaks a rule of the API."),
                        true);
                return;
            }
        }
        Scope scope = found.route().scope();
        if (scope != null && !token.get().allows(scope)) {
            reject(ctx, responseOrigin, new ApiException(403, "missing-scope",
                    "This token lacks the scope " + scope.id() + "."), true);
            return;
        }
        boolean events = found.route().path().equals(ApiRoutes.EVENTS);
        if (events && upgrade) {
            if (origin != null && !allowedOrigin) {
                reject(ctx, null, new ApiException(403, "origin-not-allowed",
                        "This origin is not in api.cors-origins."), true);
                return;
            }
        } else {
            upgrade = false;
        }
        long length = HttpUtil.getContentLength(request, -1L);
        boolean hasBody = length > 0 || HttpUtil.isTransferEncodingChunked(request);
        if (length > server.limits().maxBodyBytes()) {
            reject(ctx, responseOrigin, new ApiException(413, "body-too-large",
                    "The body is larger than " + server.limits().maxBodyBytes() + " bytes."), true);
            return;
        }
        if (hasBody) {
            String type = headers.get(HttpHeaderNames.CONTENT_TYPE);
            if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                reject(ctx, responseOrigin, new ApiException(415, "unsupported-media-type",
                        "The body must be application/json."), true);
                return;
            }
            if (HttpUtil.is100ContinueExpected(request)) {
                ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
            }
        }
        pending = new Pending(request, found, token.get(), responseOrigin, HttpUtil.isKeepAlive(request), upgrade,
                new ByteArrayOutputStream());
    }

    private void dispatch(ChannelHandlerContext ctx, Pending request) {
        if (!server.requestSlots().tryAcquire()) {
            reject(ctx, request.origin(), new ApiException(503, "busy", "The API is busy. Try again shortly."),
                    !request.keepAlive());
            return;
        }
        busy = true;
        ctx.channel().config().setAutoRead(false);
        ApiRequest apiRequest = new ApiRequest(request.head().method().name(), request.route().params(),
                request.route().query(), request.body().toByteArray(), request.token(),
                request.head().headers().get(HttpHeaderNames.IF_MATCH));
        try {
            server.executor().execute(() -> {
                FullHttpResponse response;
                try {
                    response = respond(request.route().route().handler().handle(apiRequest), request.origin());
                } catch (Exception e) {
                    response = error(map(e), request.origin());
                } finally {
                    server.requestSlots().release();
                }
                FullHttpResponse answer = response;
                ctx.channel().eventLoop().execute(() -> finish(ctx, answer, request.keepAlive()));
            });
        } catch (RejectedExecutionException e) {
            server.requestSlots().release();
            busy = false;
            ctx.channel().config().setAutoRead(true);
            reject(ctx, request.origin(), ApiException.shuttingDown(), true);
        }
    }

    private void finish(ChannelHandlerContext ctx, FullHttpResponse response, boolean keepAlive) {
        busy = false;
        boolean close = closeAfterResponse || !keepAlive;
        send(ctx, response, close);
        if (!close) {
            ctx.channel().config().setAutoRead(true);
            ctx.read();
        }
    }

    private void upgrade(ChannelHandlerContext ctx, Pending request) {
        Set<String> types;
        Set<String> services;
        try {
            types = list(request.route().query().get("types"), EventHub.TYPES, "types");
            services = list(request.route().query().get("services"), null, "services");
        } catch (ApiException e) {
            reject(ctx, request.origin(), e, true);
            return;
        }
        if (server.events().size() >= server.limits().maxWebSockets()) {
            reject(ctx, request.origin(), new ApiException(503, "too-many-streams", "Too many event streams are open."), true);
            return;
        }
        String scheme = server.settings().tls().isPresent() ? "wss" : "ws";
        String host = request.head().headers().get(HttpHeaderNames.HOST, "localhost");
        WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(
                scheme + "://" + host + ApiRoutes.EVENTS, WS_PROTOCOL,
                WebSocketDecoderConfig.newBuilder().maxFramePayloadLength(WS_MAX_FRAME).allowExtensions(false).build());
        FullHttpRequest full = new DefaultFullHttpRequest(request.head().protocolVersion(), request.head().method(),
                request.head().uri(), Unpooled.EMPTY_BUFFER, request.head().headers(), new DefaultHttpHeaders());
        WebSocketServerHandshaker handshaker = factory.newHandshaker(full);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            return;
        }
        if (!server.events().add(ctx.channel(), request.token().id(), types, services)) {
            reject(ctx, request.origin(), new ApiException(503, "too-many-streams", "Too many event streams are open."), true);
            return;
        }
        ctx.pipeline().replace(ApiServer.IDLE, ApiServer.IDLE, new IdleStateHandler(90, 30, 0, TimeUnit.SECONDS));
        ctx.pipeline().replace(this, "vectora-api-events", new EventStreamHandler(handshaker));
        handshaker.handshake(ctx.channel(), full).addListener(f -> {
            if (!f.isSuccess()) {
                ctx.channel().close();
            }
        });
    }

    private static Set<String> list(String value, Set<String> known, String name) throws ApiException {
        if (value == null || value.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>(Arrays.asList(value.split(",", -1)));
        for (String item : result) {
            boolean ok = known == null ? item.matches("[a-z0-9][a-z0-9_-]{0,63}") : known.contains(item);
            if (!ok) {
                throw new ApiException(400, "invalid-query", "\"" + name + "\" has an unknown entry.");
            }
        }
        return result;
    }

    private Optional<String> presentedToken(HttpHeaders headers, boolean upgrade) {
        String authorization = headers.get(HttpHeaderNames.AUTHORIZATION);
        if (authorization != null) {
            if (authorization.length() > BEARER.length()
                    && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
                return Optional.of(authorization.substring(BEARER.length()).strip());
            }
            return Optional.of(""); // present but not a bearer token: counts as a wrong token
        }
        if (upgrade) {
            // Browsers cannot set headers on a WebSocket; they pass the token as a subprotocol.
            String protocols = headers.get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
            if (protocols != null) {
                for (String protocol : protocols.split(",")) {
                    String p = protocol.strip();
                    if (p.startsWith(WS_BEARER)) {
                        return Optional.of(p.substring(WS_BEARER.length()));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static ApiException unauthorized() {
        return new ApiException(401, "unauthorized", "A valid API token is required (Authorization: Bearer vct_...).");
    }

    private void preflight(ChannelHandlerContext ctx, String origin, boolean keepAlive) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
        HttpHeaders h = response.headers();
        h.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        h.set(HttpHeaderNames.VARY, HttpHeaderNames.ORIGIN);
        h.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE");
        h.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Authorization, Content-Type, If-Match");
        h.set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, "600");
        h.setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        send(ctx, response, !keepAlive);
    }

    private void reject(ChannelHandlerContext ctx, String origin, ApiException error, boolean close) {
        send(ctx, error(error, origin), close || closeAfterResponse);
    }

    private FullHttpResponse respond(ApiResponse answer, String origin) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(answer.status()),
                answer.body() == null ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(answer.body()));
        for (Map.Entry<String, String> header : answer.headers().entrySet()) {
            response.headers().set(header.getKey(), header.getValue());
        }
        decorate(response, origin, answer.body() != null);
        return response;
    }

    private FullHttpResponse error(ApiException error, String origin) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(error.status()), Unpooled.wrappedBuffer(ApiJson.error(error)));
        if (error.status() == 401) {
            response.headers().set(HttpHeaderNames.WWW_AUTHENTICATE, "Bearer realm=\"vectora\"");
        }
        if (error.retryAfterSeconds() > 0) {
            response.headers().set(HttpHeaderNames.RETRY_AFTER, Long.toString(error.retryAfterSeconds()));
        }
        decorate(response, origin, true);
        return response;
    }

    private void decorate(FullHttpResponse response, String origin, boolean json) {
        HttpHeaders h = response.headers();
        if (json) {
            h.set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        }
        h.setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        h.set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        h.set("Referrer-Policy", "no-referrer");
        if (server.settings().tls().isPresent()) {
            h.set("Strict-Transport-Security", "max-age=31536000");
        }
        if (origin != null) {
            h.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            h.set(HttpHeaderNames.VARY, HttpHeaderNames.ORIGIN);
            h.set(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS, "ETag, Retry-After");
        }
    }

    private static void send(ChannelHandlerContext ctx, FullHttpResponse response, boolean close) {
        response.headers().set(HttpHeaderNames.CONNECTION, close ? HttpHeaderValues.CLOSE : HttpHeaderValues.KEEP_ALIVE);
        var future = ctx.writeAndFlush(response);
        if (close) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private ApiException map(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new ApiException(503, "interrupted", "The request was interrupted.");
        }
        return switch (e) {
            case ApiException api -> api;
            case ApiBackendException backend -> new ApiException(switch (backend.reason()) {
                case NOT_FOUND -> 404;
                case CONFLICT -> 409;
                case UNAVAILABLE -> 503;
                case INVALID -> 422;
            }, backend.reason().name().toLowerCase(Locale.ROOT).replace('_', '-'), clean(backend.getMessage()));
            case ConfigException config -> ApiException.invalidConfig(config.errors());
            case IOException io -> {
                LOG.log(System.Logger.Level.WARNING, "API request failed: {0}", io.getClass().getSimpleName());
                yield new ApiException(500, "io-error", "The node could not read or write a file.");
            }
            default -> {
                LOG.log(System.Logger.Level.ERROR, "API request failed", e);
                yield new ApiException(500, "internal-error", "Internal error.");
            }
        };
    }

    /** Redacts, removes control characters and cuts a backend message. */
    private String clean(String message) {
        String text = server.redact(message == null ? "" : message).replaceAll("\\p{Cntrl}", "?");
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.log(System.Logger.Level.DEBUG, "API connection failed: {0}", cause.getClass().getSimpleName());
        ctx.close();
    }
}
