package net.vectolyn.cloud.api;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

/**
 * One open event stream. The stream only sends; frames from the client other than ping, pong and
 * close are ignored. The server pings after 30 s without traffic and closes the stream if nothing
 * came back for 90 s.
 */
final class EventStreamHandler extends SimpleChannelInboundHandler<Object> {

    private final WebSocketServerHandshaker handshaker;

    EventStreamHandler(WebSocketServerHandshaker handshaker) {
        this.handshaker = handshaker;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof CloseWebSocketFrame close) {
            handshaker.close(ctx.channel(), close.retain());
        } else if (msg instanceof PingWebSocketFrame ping) {
            ctx.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
        } else if (msg instanceof WebSocketFrame) {
            // pong, text or binary: nothing to do, the stream only sends
            return;
        }
        // anything else (the end of the upgrade request) is dropped
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent idle) {
            if (idle.state() == IdleState.WRITER_IDLE) {
                ctx.writeAndFlush(new PingWebSocketFrame());
            } else if (idle.state() == IdleState.READER_IDLE) {
                ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.NORMAL_CLOSURE, "idle"))
                        .addListener(f -> ctx.close());
            }
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
