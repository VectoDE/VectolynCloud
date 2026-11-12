// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import net.vectolyn.cloud.api.auth.ApiToken;
import net.vectolyn.cloud.api.auth.RateLimiter;
import net.vectolyn.cloud.api.auth.Scope;
import net.vectolyn.cloud.api.backend.ApiBackendException;
import net.vectolyn.cloud.api.event.ApiEvent;
import net.vectolyn.cloud.api.http.ApiException;
import net.vectolyn.cloud.api.http.ApiJson;
import net.vectolyn.cloud.api.http.ApiRequest;
import net.vectolyn.cloud.api.http.ApiResponse;
import net.vectolyn.cloud.api.http.ApiRoutes;
import net.vectolyn.cloud.api.http.Router;
import net.vectolyn.cloud.config.error.ConfigException;
import org.jspecify.annotations.Nullable;

/**
 * One HTTP connection of the API. Order of the checks for every request, all before the body is
 * read:
 * <ol>
 *   <li>rate limit of the client address (429);</li>
 *   <li>CORS preflight from an allowed origin (204, the only answer without a token);</li>
 *   <li>too many wrong tokens from this address (429), then the token (401); the one-time setup
 *       token counts only for {@code POST /api/v1/setup/claim};</li>
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
    private static final String WS_PROTOCOL = "vectolyn.v1";
    private static final String WS_BEARER = "vectolyn.bearer.";
    private static final int WS_MAX_FRAME = 4096;
    private static final Pattern COMMA = Pattern.compile(",");

    private final ApiServer server;

    private @Nullable Pending pending;
    private boolean busy;
    private boolean closeAfterResponse;
    /** A response with {@code Connection: close} is on its way; the rest of the input is dropped. */
    private boolean lingering;

    /** How long a closing connection keeps reading what the client still sends. */
    static final int LINGER_SECONDS = 2;

    ApiHttpHandler(ApiServer server) {
        super(false);
        this.server = server;
    }

    /** A request whose head passed every check and whose body is being collected. */
    private record Pending(HttpRequest head, Router.Resolution.Found<ApiRoutes.Handler> route,
                           @Nullable ApiToken token, @Nullable String origin, boolean keepAlive, boolean upgrade,
                           ByteArrayOutputStream body, @Nullable String setupToken) {
    }

    /** Key of the setup token in the token rate limiter; API token ids are 8 hex characters. */
    private static final String SETUP_LIMIT_KEY = "setup";

    // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
        try {
            if (lingering) {
                return; // released below; the connection closes after its last response
            }
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

    // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
    @SuppressWarnings("FutureReturnValueIgnored")
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
        // The setup token works for exactly one endpoint; anywhere else it is a wrong token.
        String setupToken = token.isEmpty() && method.equals("POST") && isSetupClaim(request.uri())
                && server.tokens().setupAdmin(presented.get()).isPresent() ? presented.get() : null;
        if (token.isEmpty() && setupToken == null) {
            server.authFailureLimiter().tryAcquire(client);
            reject(ctx, responseOrigin, unauthorized(), true);
            return;
        }
        wait = server.tokenLimiter().tryAcquire(setupToken != null ? SETUP_LIMIT_KEY : token.get().id());
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
        if (setupToken == null && scope != null && !token.get().allows(scope)) {
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
        pending = new Pending(request, found, token.orElse(null), responseOrigin, HttpUtil.isKeepAlive(request), upgrade,
                new ByteArrayOutputStream(), setupToken);
    }

    /** Whether the request target is {@value ApiRoutes#SETUP_CLAIM}, without a query. */
    private static boolean isSetupClaim(String uri) {
        return ApiRoutes.SETUP_CLAIM.equals(uri);
    }

    private void dispatch(ChannelHandlerContext ctx, Pending request) {
        if (!server.requestSlots().tryAcquire()) {
            reject(ctx, request.origin(), new ApiException(503, "busy", "The API is busy. Try again shortly."),
                    !request.keepAlive());
            return;
        }
        busy = true;
        ctx.channel().config().setAutoRead(false);
        // A request with a setup token has no API token; it is answered by claim() and needs no ApiRequest.
        ApiToken token = request.token();
        ApiRequest apiRequest = token == null ? null : new ApiRequest(request.head().method().name(),
                request.route().params(), request.route().query(), request.body().toByteArray(), token,
                request.head().headers().get(HttpHeaderNames.IF_MATCH));
        String client = ClientAddress.key(ctx.channel().remoteAddress());
        try {
            server.executor().execute(() -> {
                FullHttpResponse response;
                try {
                    String setupToken = request.setupToken();
                    response = respond(setupToken != null ? claim(setupToken, client)
                            : request.route().route().handler().handle(Objects.requireNonNull(apiRequest, "token")),
                            request.origin());
                } catch (Exception e) {
                    response = error(map(e), request.origin());
                } finally {
                    server.requestSlots().release();
                }
                rateLimitHeaders(response, request.token() != null ? request.token().id() : SETUP_LIMIT_KEY);
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

    /**
     * Exchanges the setup token for the administrator's API token. A token that was used or expired
     * between the check of the head and now counts as a wrong token.
     */
    private ApiResponse claim(String setupToken, String client) throws Exception {
        Optional<net.vectolyn.cloud.api.auth.ApiTokens.Created> created = server.tokens().claimSetupToken(setupToken);
        if (created.isEmpty()) {
            server.authFailureLimiter().tryAcquire(client);
            throw unauthorized();
        }
        LOG.log(System.Logger.Level.INFO, "API: the setup token was exchanged for the admin token {0} ({1})",
                created.get().info().name(), created.get().info().id());
        return ApiRoutes.claimed(created.get(), server.tokens().clock().instant());
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

    /**
     * {@code X-RateLimit-Limit} (the bucket of the token) and {@code X-RateLimit-Remaining} (what
     * it holds now), so a script can slow down before it gets {@code 429}.
     */
    private void rateLimitHeaders(FullHttpResponse response, String limitKey) {
        RateLimiter<String> limiter = server.tokenLimiter();
        response.headers().set("X-RateLimit-Limit", Integer.toString(limiter.burst()));
        response.headers().set("X-RateLimit-Remaining", Integer.toString(limiter.remaining(limitKey)));
    }

    // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
    @SuppressWarnings("FutureReturnValueIgnored")
    private void upgrade(ChannelHandlerContext ctx, Pending request) {
        ApiToken token = Objects.requireNonNull(request.token(), "token");
        Set<String> types;
        Set<String> services;
        try {
            types = list(request.route().query().get("types"), EventHub.TYPES, "types");
            services = list(request.route().query().get("services"), null, "services");
            types = permittedTypes(token, types);
        } catch (ApiException e) {
            reject(ctx, request.origin(), e, true);
            return;
        }
        String offered = request.head().headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        if (offered != null && Arrays.stream(offered.split(",")).map(String::strip).noneMatch(WS_PROTOCOL::equals)) {
            // Netty would log the offered list (with a bearer subprotocol in it) when none fits.
            reject(ctx, request.origin(), new ApiException(400, "subprotocol-required",
                    "Offer the subprotocol " + WS_PROTOCOL + "."), true);
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
        if (!server.events().add(ctx.channel(), token.id(), types, services)) {
            reject(ctx, request.origin(), new ApiException(503, "too-many-streams", "Too many event streams are open."), true);
            return;
        }
        if (!server.tokens().isValid(token.id())) {
            // Revoked between the check of the head and now; a revoke from here on closes the stream.
            reject(ctx, request.origin(), unauthorized(), true);
            return;
        }
        ctx.pipeline().replace(ApiServer.IDLE, ApiServer.IDLE, new IdleStateHandler(90, 30, 0, TimeUnit.SECONDS));
        ctx.pipeline().replace(this, "vectolyn-api-events", new EventStreamHandler(handshaker));
        handshaker.handshake(ctx.channel(), full).addListener(f -> {
            if (!f.isSuccess()) {
                ctx.channel().close();
            }
        });
    }

    /**
     * The event types a stream receives. Asked for explicitly, every type needs its scope, otherwise
     * the handshake is refused with {@code 403 missing-scope}. Without {@code types} the stream gets
     * every type the token may see; a token that may see none is refused as well.
     */
    static Set<String> permittedTypes(ApiToken token, Set<String> asked) throws ApiException {
        Set<String> result = new TreeSet<>();
        if (asked.isEmpty()) {
            ApiEvent.TYPES.forEach((type, scope) -> {
                if (token.allows(scope)) {
                    result.add(type);
                }
            });
            if (result.isEmpty()) {
                throw new ApiException(403, "missing-scope",
                        "This token lacks every scope of the event stream (services:read, node:read or admin).");
            }
            return result;
        }
        for (String type : asked) {
            Scope scope = Objects.requireNonNull(ApiEvent.scopeOf(type), type);
            if (!token.allows(scope)) {
                throw new ApiException(403, "missing-scope",
                        "The event type " + type + " needs the scope " + scope.id() + ".");
            }
            result.add(type);
        }
        return result;
    }

    private static Set<String> list(@Nullable String value, @Nullable Set<String> known, String name) throws ApiException {
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
                for (String protocol : COMMA.splitAsStream(protocols).toList()) {
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

    private void reject(ChannelHandlerContext ctx, @Nullable String origin, ApiException error, boolean close) {
        send(ctx, error(error, origin), close || closeAfterResponse);
    }

    private FullHttpResponse respond(ApiResponse answer, @Nullable String origin) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(answer.status()),
                answer.body() == null ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(answer.body()));
        for (Map.Entry<String, String> header : answer.headers().entrySet()) {
            response.headers().set(header.getKey(), header.getValue());
        }
        decorate(response, origin, answer.body() != null);
        return response;
    }

    private FullHttpResponse error(ApiException error, @Nullable String origin) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(error.status()), Unpooled.wrappedBuffer(ApiJson.error(error)));
        if (error.status() == 401) {
            response.headers().set(HttpHeaderNames.WWW_AUTHENTICATE, "Bearer realm=\"vectolyn\"");
        }
        if (error.retryAfterSeconds() > 0) {
            response.headers().set(HttpHeaderNames.RETRY_AFTER, Long.toString(error.retryAfterSeconds()));
        }
        decorate(response, origin, true);
        return response;
    }

    private void decorate(FullHttpResponse response, @Nullable String origin, boolean json) {
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
            h.set(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS,
                    "ETag, Retry-After, Link, X-Total-Count, X-RateLimit-Limit, X-RateLimit-Remaining");
        }
    }

    private void send(ChannelHandlerContext ctx, FullHttpResponse response, boolean close) {
        response.headers().set(HttpHeaderNames.CONNECTION, close ? HttpHeaderValues.CLOSE : HttpHeaderValues.KEEP_ALIVE);
        var future = ctx.writeAndFlush(response);
        if (close) {
            lingering = true;
            future.addListener(done -> lingeringClose(ctx));
        }
    }

    /**
     * Ends a connection after a response that closes it. Closing at once while the client is still
     * sending (a body above the limit) makes the system answer with a reset, and the client may then
     * lose the response; Windows does that reliably. So the output is shut down first, whatever the
     * client still sends is read and dropped, and the connection closes when the client closes its
     * side, or after {@value #LINGER_SECONDS} seconds at the latest.
     */
    // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
    @SuppressWarnings("FutureReturnValueIgnored")
    private static void lingeringClose(ChannelHandlerContext ctx) {
        if (!(ctx.channel() instanceof SocketChannel socket) || !socket.isActive()) {
            ctx.close();
            return;
        }
        socket.shutdownOutput().addListener(shut -> {
            if (!shut.isSuccess()) {
                ctx.close();
                return;
            }
            socket.config().setAutoRead(true);
            ctx.executor().schedule(() -> ctx.close(), LINGER_SECONDS, TimeUnit.SECONDS);
        });
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

    // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.log(System.Logger.Level.DEBUG, "API connection failed: {0}", cause.getClass().getSimpleName());
        ctx.close();
    }
}
