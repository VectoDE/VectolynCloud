// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;

class BodyLimitHandlerTest {

    private static HttpRequest post(String uri, long length) {
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri);
        if (length >= 0) {
            request.headers().set(HttpHeaderNames.CONTENT_LENGTH, length);
        }
        return request;
    }

    private static int status(EmbeddedChannel channel) {
        Object out = channel.readOutbound();
        if (!(out instanceof FullHttpResponse response)) {
            return 0;
        }
        int code = response.status().code();
        response.release();
        return code;
    }

    @Test
    void smallBodiesForEverythingButUploads() {
        Semaphore uploads = new Semaphore(BodyLimitHandler.UPLOADS);
        EmbeddedChannel announced = new EmbeddedChannel(new BodyLimitHandler(uploads, false));
        announced.writeInbound(post("/api/panel/login", BodyLimitHandler.SMALL + 1L));
        assertEquals(413, status(announced));
        assertFalse(announced.isOpen());

        EmbeddedChannel chunked = new EmbeddedChannel(new BodyLimitHandler(uploads, false));
        chunked.writeInbound(post("/api/panel/login", -1));
        chunked.writeInbound(new DefaultHttpContent(Unpooled.wrappedBuffer(new byte[BodyLimitHandler.SMALL])));
        assertEquals(0, status(chunked));
        chunked.writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(new byte[1])));
        assertEquals(413, status(chunked), "counted while it arrives, not only from Content-Length");
        drop(chunked);

        EmbeddedChannel upload = new EmbeddedChannel(new BodyLimitHandler(uploads, false));
        upload.writeInbound(post("/api/panel/files/write?service=a&path=b", 4L * 1024 * 1024));
        upload.writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(new byte[4 * 1024 * 1024])));
        assertEquals(0, status(upload), "an upload may be larger");
        assertTrue(upload.isOpen());
        drop(upload);
        upload.close();
        assertEquals(BodyLimitHandler.UPLOADS, uploads.availablePermits(), "the permit comes back");

        EmbeddedChannel tooBig = new EmbeddedChannel(new BodyLimitHandler(uploads, false));
        tooBig.writeInbound(post("/api/panel/files/write", PanelServer.MAX_BODY_BYTES + 1L));
        assertEquals(413, status(tooBig));
        assertEquals(BodyLimitHandler.UPLOADS, uploads.availablePermits());
    }

    @Test
    void onlyAFewUploadsAtOnce() {
        Semaphore uploads = new Semaphore(BodyLimitHandler.UPLOADS);
        List<EmbeddedChannel> open = new ArrayList<>();
        for (int i = 0; i < BodyLimitHandler.UPLOADS; i++) {
            EmbeddedChannel channel = new EmbeddedChannel(new BodyLimitHandler(uploads, false));
            channel.writeInbound(post("/api/panel/files/write", 10));
            open.add(channel);
        }
        EmbeddedChannel fifth = new EmbeddedChannel(new BodyLimitHandler(uploads, false));
        fifth.writeInbound(post("/api/panel/files/write", 10));
        assertEquals(503, status(fifth));
        open.forEach(channel -> {
            drop(channel);
            channel.close();
        });
        assertEquals(BodyLimitHandler.UPLOADS, uploads.availablePermits());
    }

    private static void drop(EmbeddedChannel channel) {
        Object in;
        while ((in = channel.readInbound()) != null) {
            ReferenceCountUtil.release(in);
        }
    }
}
