// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.DefaultHttp2PingFrame;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * State of one gRPC connection, kept on its event loop (the stream channels share it).
 *
 * <p>Only authenticated calls count as activity: a connection without one for the idle timeout is
 * closed, whatever frames (PING, SETTINGS, unfinished or refused streams) the client sends, so an
 * unauthenticated client cannot hold one of the connections that REST shares. While a call is open
 * (an {@code Events} stream that has nothing to send) the server pings instead, so a dead client is
 * noticed by the transport. Request bodies that are still arriving share one budget of one
 * framed message ({@code maxBodyBytes} plus the 5-byte prefix) per connection, the same memory a
 * REST connection may hold; a second large upload in parallel on the same connection is refused
 * with {@code UNAVAILABLE} and can be retried.
 */
final class GrpcConnection extends ChannelInboundHandlerAdapter {

    private final long idleNanos;
    private final long bodyBudget;
    private final LongSupplier nanoTime;
    private int calls;
    private long buffered;
    private long lastBusy;
    private @Nullable ScheduledFuture<?> check;

    GrpcConnection(int idleTimeoutSeconds, long bodyBudget, LongSupplier nanoTime) {
        this.idleNanos = TimeUnit.SECONDS.toNanos(idleTimeoutSeconds);
        this.bodyBudget = bodyBudget;
        this.nanoTime = nanoTime;
    }

    /** The state of the connection a stream channel belongs to, or null. */
    static @Nullable GrpcConnection of(Channel stream) {
        Channel parent = stream.parent();
        return parent == null ? null : parent.pipeline().get(GrpcConnection.class);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        lastBusy = nanoTime.getAsLong();
        long period = Math.max(TimeUnit.SECONDS.toNanos(1), idleNanos / 2);
        check = ctx.executor().scheduleAtFixedRate(() -> check(ctx), period, period, TimeUnit.NANOSECONDS);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (check != null) {
            check.cancel(false);
        }
    }

    // Netty writes and closes are fire-and-forget; a failed write closes the channel through the pipeline.
    @SuppressWarnings("FutureReturnValueIgnored")
    private void check(ChannelHandlerContext ctx) {
        long now = nanoTime.getAsLong();
        if (calls > 0) {
            lastBusy = now;
            ctx.writeAndFlush(new DefaultHttp2PingFrame(now));
        } else if (now - lastBusy >= idleNanos) {
            ctx.close();
        }
    }

    /** An authenticated call starts; {@link #end()} must follow exactly once. */
    void begin() {
        calls++;
        lastBusy = nanoTime.getAsLong();
    }

    /** An authenticated call ended. */
    void end() {
        calls--;
        lastBusy = nanoTime.getAsLong();
    }

    /**
     * Takes bytes of a request body that is still arriving from the connection's budget.
     *
     * @param bytes the bytes
     * @return false if the budget is used up (nothing is taken then)
     */
    boolean reserve(long bytes) {
        if (buffered + bytes > bodyBudget) {
            return false;
        }
        buffered += bytes;
        return true;
    }

    /** Gives bytes back to the budget. */
    void release(long bytes) {
        buffered -= bytes;
    }

    /** Open authenticated calls, for tests. */
    int calls() {
        return calls;
    }
}
