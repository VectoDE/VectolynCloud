// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.ssl.SslContext;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.vectolyn.cloud.api.grpc.Proto;
import net.vectolyn.cloud.api.grpc.ProtoSchema;

/** A minimal gRPC client over HTTP/2 (prior knowledge, or TLS with ALPN h2) for the tests. */
final class GrpcTestClient implements AutoCloseable {

    static final ProtoSchema SCHEMA = GrpcService.load();

    private final EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private final Channel connection;

    GrpcTestClient(InetSocketAddress address, SslContext ssl) throws InterruptedException {
        Bootstrap bootstrap = new Bootstrap().group(group).channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (ssl != null) {
                            ch.pipeline().addLast(ssl.newHandler(ch.alloc(), "127.0.0.1", address.getPort()));
                        }
                        ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
                        ch.pipeline().addLast(new Http2MultiplexHandler(new ChannelInboundHandlerAdapter()));
                    }
                });
        connection = bootstrap.connect(address).sync().channel();
    }

    /** Headers of a gRPC request. */
    static Http2Headers headers(String method, String token) {
        Http2Headers headers = new DefaultHttp2Headers().method("POST").scheme("http").authority("127.0.0.1")
                .path(SCHEMA.path(method)).set("content-type", "application/grpc").set("te", "trailers");
        if (token != null) {
            headers.set("authorization", "Bearer " + token);
        }
        return headers;
    }

    /** Opens a stream and sends the headers and, if not null, the body with end of stream. */
    Call open(Http2Headers headers, byte[] body) throws InterruptedException {
        return open(headers, body, true);
    }

    /** Opens a stream; with {@code end} false the client never finishes sending. */
    Call open(Http2Headers headers, byte[] body, boolean end) throws InterruptedException {
        Call call = new Call();
        Http2StreamChannel stream = new Http2StreamChannelBootstrap(connection).handler(call).open().sync().getNow();
        call.stream = stream;
        stream.write(new DefaultHttp2HeadersFrame(headers, end && body == null));
        if (body != null) {
            stream.write(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(body), end));
        }
        stream.flush();
        return call;
    }

    /** Whether the server closed the connection within the given time. */
    boolean closedWithin(long seconds) throws InterruptedException {
        return connection.closeFuture().await(seconds, TimeUnit.SECONDS);
    }

    /** A unary call with a request message given as a map. */
    Reply unary(String method, String token, Map<String, ?> request) throws Exception {
        byte[] message = Proto.encode(SCHEMA.methods().get(method).request(), request);
        return open(headers(method, token), GrpcStatus.frame(message)).reply();
    }

    /** The protocol negotiated through ALPN, or null without TLS. */
    String alpn() {
        io.netty.handler.ssl.SslHandler ssl = connection.pipeline().get(io.netty.handler.ssl.SslHandler.class);
        return ssl == null ? null : ssl.applicationProtocol();
    }

    @Override
    public void close() {
        connection.close().syncUninterruptibly();
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    /** One stream: the response headers, every data byte and the trailers as they arrive. */
    static final class Call extends SimpleChannelInboundHandler<Object> {

        Http2StreamChannel stream;
        final CompletableFuture<Http2Headers> headers = new CompletableFuture<>();
        final CompletableFuture<Http2Headers> trailers = new CompletableFuture<>();
        final BlockingQueue<byte[]> messages = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object frame) {
            if (frame instanceof Http2HeadersFrame h) {
                if (!headers.isDone() && h.headers().status() != null) {
                    headers.complete(h.headers());
                    if (h.isEndStream()) {
                        trailers.complete(h.headers()); // trailers-only answer
                    }
                } else {
                    trailers.complete(h.headers());
                }
            } else if (frame instanceof Http2DataFrame d) {
                ByteBuf content = d.content();
                byte[] chunk = new byte[content.readableBytes()];
                content.readBytes(chunk);
                pending.writeBytes(chunk);
                split();
                if (d.isEndStream() && !trailers.isDone()) {
                    trailers.complete(new DefaultHttp2Headers());
                }
            }
        }

        private void split() {
            byte[] all = pending.toByteArray();
            int at = 0;
            while (all.length - at >= 5) {
                int length = ((all[at + 1] & 0xFF) << 24) | ((all[at + 2] & 0xFF) << 16) | ((all[at + 3] & 0xFF) << 8)
                        | (all[at + 4] & 0xFF);
                if (all.length - at - 5 < length) {
                    break;
                }
                byte[] message = new byte[length];
                System.arraycopy(all, at + 5, message, 0, length);
                messages.add(message);
                at += 5 + length;
            }
            pending.reset();
            pending.write(all, at, all.length - at);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            headers.complete(null);
            trailers.complete(null);
        }

        /** Waits for the end of the stream. */
        Reply reply() throws Exception {
            Http2Headers end = trailers.get(10, TimeUnit.SECONDS);
            List<byte[]> received = new ArrayList<>();
            messages.drainTo(received);
            return new Reply(headers.getNow(null), received, end);
        }
    }

    /**
     * A finished call.
     *
     * @param headers  the response headers
     * @param messages the response messages
     * @param trailers the trailers (the headers for a trailers-only answer)
     */
    record Reply(Http2Headers headers, List<byte[]> messages, Http2Headers trailers) {

        int status() {
            CharSequence status = trailers == null ? null : trailers.get("grpc-status");
            return status == null ? -1 : Integer.parseInt(status.toString());
        }

        String code() {
            CharSequence code = trailers == null ? null : trailers.get("vectolyn-code");
            return code == null ? null : code.toString();
        }

        String httpStatus() {
            return headers == null || headers.status() == null ? null : headers.status().toString();
        }

        Map<String, Object> message(String method) throws Proto.ProtoException {
            if (messages.size() != 1) {
                throw new AssertionError("expected one message, got " + messages.size() + ", trailers " + trailers);
            }
            return Proto.decode(SCHEMA.methods().get(method).response(), messages.getFirst());
        }
    }
}
