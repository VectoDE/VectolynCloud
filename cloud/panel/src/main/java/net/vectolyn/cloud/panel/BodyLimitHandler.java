// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.util.ReferenceCountUtil;
import java.util.concurrent.Semaphore;
import net.vectolyn.cloud.panel.http.Responses;

/**
 * Limits request bodies before they are collected in memory: {@value #SMALL} bytes for every
 * request, {@value PanelServer#MAX_BODY_BYTES} bytes for a file upload
 * ({@code POST /api/panel/files/write}) or a configuration archive ({@code POST /api/panel/config/import}
 * and its preview), and at most {@value #UPLOADS} uploads at a time on the
 * whole server. A body over its limit is answered with {@code 413}, a fifth upload with
 * {@code 503}; both close the connection.
 */
final class BodyLimitHandler extends ChannelInboundHandlerAdapter {

    /** Largest body of any request but an upload. */
    static final int SMALL = 256 * 1024;
    /** Uploads collected at the same time. */
    static final int UPLOADS = 4;

    private final Semaphore uploads;
    private final boolean secure;
    private long limit = SMALL;
    private long seen;
    private boolean holdsUpload;
    private boolean refused;

    BodyLimitHandler(Semaphore uploads, boolean secure) {
        this.uploads = uploads;
        this.secure = secure;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (refused) {
            ReferenceCountUtil.release(msg);
            return;
        }
        if (msg instanceof HttpRequest request) {
            release();
            seen = 0;
            limit = SMALL;
            if (request.method().equals(HttpMethod.POST) && isUpload(request.uri())) {
                if (!uploads.tryAcquire()) {
                    refuse(ctx, msg, 503, "busy", "Other uploads are running; try again in a moment.");
                    return;
                }
                holdsUpload = true;
                limit = PanelServer.MAX_BODY_BYTES;
            }
            if (HttpUtil.getContentLength(request, -1L) > limit) {
                refuse(ctx, msg, 413, "body-too-large", "The body is larger than " + limit / 1024 + " KiB.");
                return;
            }
        }
        if (msg instanceof HttpContent content) {
            seen += content.content().readableBytes();
            if (seen > limit) {
                refuse(ctx, msg, 413, "body-too-large", "The body is larger than " + limit / 1024 + " KiB.");
                return;
            }
        }
        // An upload keeps its permit until the next request on the connection or its end.
        ctx.fireChannelRead(msg);
    }

    static boolean isUpload(String uri) {
        int q = uri.indexOf('?');
        String path = q < 0 ? uri : uri.substring(0, q);
        return path.equals("/api/panel/files/write") || path.equals("/api/panel/config/import")
                || path.equals("/api/panel/config/import/preview");
    }

    private void refuse(ChannelHandlerContext ctx, Object msg, int status, String code, String message) {
        ReferenceCountUtil.release(msg);
        refused = true;
        release();
        FullHttpResponse response = Responses.error(status, code, message, secure);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void release() {
        if (holdsUpload) {
            holdsUpload = false;
            uploads.release();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        release();
        super.channelInactive(ctx);
    }
}
