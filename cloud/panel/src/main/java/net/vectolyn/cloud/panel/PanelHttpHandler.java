// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.IdleStateEvent;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.vectolyn.cloud.panel.http.RequestTarget;
import net.vectolyn.cloud.panel.http.Responses;
import net.vectolyn.cloud.panel.web.WebAssets;
import org.jspecify.annotations.Nullable;

/**
 * One connection of the panel server. Every request is checked in this order: decodable (400),
 * rate limit of the client address (429), {@code Host} (421), request target (400). Then
 * {@code /api/...} goes to the panel's endpoints and everything else to the frontend files
 * ({@code GET} and {@code HEAD} only).
 */
final class PanelHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final System.Logger LOG = System.getLogger(PanelHttpHandler.class.getName());

    private final PanelServer server;

    PanelHttpHandler(PanelServer server) {
        this.server = server;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (!request.decoderResult().isSuccess()) {
            send(ctx, Responses.text(400, "Bad request.", server.secure()), false);
            return;
        }
        long wait = server.addressLimiter().tryAcquire(PanelServer.clientKey(ctx.channel().remoteAddress()));
        if (wait > 0) {
            FullHttpResponse limited = Responses.error(429, "rate-limited", "Too many requests.", server.secure());
            limited.headers().set(HttpHeaderNames.RETRY_AFTER, Long.toString(wait));
            send(ctx, limited, false);
            return;
        }
        String host = request.headers().get(HttpHeaderNames.HOST);
        if (host == null || !hostAllowed(host)) {
            send(ctx, Responses.text(421, "This host name is not served here.", server.secure()), false);
            return;
        }
        Optional<RequestTarget> target = RequestTarget.parse(request.uri());
        if (target.isEmpty()) {
            send(ctx, Responses.text(400, "The request target breaks a rule of the panel.", server.secure()), false);
            return;
        }
        PanelApi api = server.api();
        if (target.get().path().startsWith("/api/") && api != null) {
            // Password hashing, file writes and calls to a sign-on provider block: they run on a
            // virtual thread, and the connection reads nothing else until the answer is out.
            int q = request.uri().indexOf('?');
            byte[] body = new byte[request.content().readableBytes()];
            request.content().getBytes(request.content().readerIndex(), body);
            PanelApi.Call call = new PanelApi.Call(request.method().name(), target.get().path(), target.get().query(),
                    q < 0 ? "" : request.uri().substring(q + 1), body, request.headers().copy(),
                    PanelServer.clientKey(ctx.channel().remoteAddress()));
            ctx.channel().config().setAutoRead(false);
            try {
                server.executor().execute(() -> {
                    FullHttpResponse response;
                    try {
                        response = api.handle(call);
                    } catch (RuntimeException e) {
                        LOG.log(System.Logger.Level.ERROR, "Panel endpoint failed: {0}", e.getClass().getName());
                        response = Responses.error(500, "internal", "The panel failed.", server.secure());
                    }
                    send(ctx, response, keepAlive);
                    ctx.channel().config().setAutoRead(true);
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                send(ctx, Responses.error(503, "shutting-down", "The panel is stopping.", server.secure()), false);
            }
        } else if (target.get().path().startsWith("/api/")) {
            send(ctx, api(request, target.get()), keepAlive);
        } else {
            send(ctx, file(request, target.get()), keepAlive);
        }
    }

    private boolean hostAllowed(String host) {
        if (host.length() > 255) {
            return false;
        }
        return server.allowedHosts().isEmpty() || server.allowedHosts().contains(PanelServer.hostOf(host.strip()));
    }

    /** The panel's own JSON endpoints. */
    private FullHttpResponse api(FullHttpRequest request, RequestTarget target) {
        if (target.path().equals("/api/panel/info")) {
            if (!request.method().equals(HttpMethod.GET)) {
                return methodNotAllowed("GET");
            }
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", "VectolynCloud");
            info.put("version", server.version());
            info.put("frontend", !server.assets().placeholder());
            return Responses.json(200, info, server.secure());
        }
        return Responses.error(404, "not-found", "No such endpoint.", server.secure());
    }

    /** A file of the frontend, with ETag, caching, compression and the page's CSP. */
    private FullHttpResponse file(FullHttpRequest request, RequestTarget target) {
        boolean head = request.method().equals(HttpMethod.HEAD);
        if (!head && !request.method().equals(HttpMethod.GET)) {
            return methodNotAllowed("GET, HEAD");
        }
        Optional<WebAssets.Asset> asset = server.assets().get(target.file());
        if (asset.isEmpty() && !target.path().endsWith("/")
                && server.assets().get(target.path().substring(1) + "/index.html").isPresent()) {
            // A page folder without its slash: send the browser to the canonical address.
            FullHttpResponse redirect = Responses.text(308, "Moved.", server.secure());
            redirect.headers().set(HttpHeaderNames.LOCATION, target.path() + "/");
            return redirect;
        }
        int status = 200;
        if (asset.isEmpty()) {
            status = 404;
            asset = server.assets().get("404.html");
            if (asset.isEmpty()) {
                return Responses.text(404, "Not found.", server.secure());
            }
        }
        WebAssets.Asset file = asset.get();
        HttpHeaders requestHeaders = request.headers();
        String ifNoneMatch = requestHeaders.get(HttpHeaderNames.IF_NONE_MATCH);
        if (status == 200 && ifNoneMatch != null && matches(ifNoneMatch, file.etag())) {
            FullHttpResponse notModified = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.NOT_MODIFIED, Unpooled.EMPTY_BUFFER);
            fileHeaders(notModified.headers(), file);
            return notModified;
        }
        String accept = requestHeaders.get(HttpHeaderNames.ACCEPT_ENCODING, "");
        byte @Nullable [] compressed = file.gzip();
        boolean gzip = compressed != null && accept.toLowerCase(Locale.ROOT).contains("gzip");
        byte[] body = gzip && compressed != null ? compressed : file.body();
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status),
                head ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(body));
        HttpHeaders h = response.headers();
        fileHeaders(h, file);
        h.set(HttpHeaderNames.CONTENT_TYPE, file.contentType());
        if (gzip) {
            h.set(HttpHeaderNames.CONTENT_ENCODING, HttpHeaderValues.GZIP);
        }
        h.setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        if (status == 404) {
            h.set(HttpHeaderNames.CACHE_CONTROL, "no-store");
            h.remove(HttpHeaderNames.ETAG);
        }
        return response;
    }

    private void fileHeaders(HttpHeaders h, WebAssets.Asset file) {
        h.set(HttpHeaderNames.ETAG, file.etag());
        h.set(HttpHeaderNames.CACHE_CONTROL, file.cacheControl());
        if (file.gzip() != null) {
            h.set(HttpHeaderNames.VARY, "Accept-Encoding");
        }
        String csp = file.csp();
        h.set("Content-Security-Policy", csp != null ? csp : "default-src 'none'; frame-ancestors 'none'");
        Responses.common(h, server.secure());
    }

    /** Whether an {@code If-None-Match} list names the ETag (weak comparison, as RFC 9110 asks). */
    static boolean matches(String ifNoneMatch, String etag) {
        String strong = etag.startsWith("W/") ? etag.substring(2) : etag;
        for (String candidate : ifNoneMatch.split(",", -1)) {
            String value = candidate.strip();
            if (value.equals("*")) {
                return true;
            }
            if (value.startsWith("W/")) {
                value = value.substring(2);
            }
            if (value.equals(strong)) {
                return true;
            }
        }
        return false;
    }

    private FullHttpResponse methodNotAllowed(String allowed) {
        FullHttpResponse response = Responses.error(405, "method-not-allowed", "Allowed: " + allowed + ".",
                server.secure());
        response.headers().set(HttpHeaderNames.ALLOW, allowed);
        return response;
    }

    private static void send(ChannelHandlerContext ctx, FullHttpResponse response, boolean keepAlive) {
        if (!response.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        }
        response.headers().set(HttpHeaderNames.CONNECTION, keepAlive ? HttpHeaderValues.KEEP_ALIVE : HttpHeaderValues.CLOSE);
        var future = ctx.writeAndFlush(response);
        if (!keepAlive) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    // Netty closes are fire-and-forget; the channel's close future reports the end to the pipeline.
    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.log(System.Logger.Level.DEBUG, "Panel connection failed: {0}", cause.getClass().getSimpleName());
        ctx.close();
    }
}
