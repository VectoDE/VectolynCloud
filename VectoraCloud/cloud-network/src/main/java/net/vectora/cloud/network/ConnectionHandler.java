package net.vectora.cloud.network;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.concurrent.ScheduledFuture;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import net.vectora.protocol.IncompatibleVersionException;
import net.vectora.protocol.PeerId;
import net.vectora.protocol.Protocol;
import net.vectora.protocol.ProtocolException;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.AuthResult;
import net.vectora.protocol.message.ChannelMessage;
import net.vectora.protocol.message.Disconnect;
import net.vectora.protocol.message.Envelope;
import net.vectora.protocol.message.Hello;
import net.vectora.protocol.message.Message;
import net.vectora.protocol.message.NetworkKick;
import net.vectora.protocol.message.Ping;
import net.vectora.protocol.message.PlayerJoin;
import net.vectora.protocol.message.PlayerQuit;
import net.vectora.protocol.message.PlayerSwitch;
import net.vectora.protocol.message.Pong;
import net.vectora.protocol.message.Request;
import net.vectora.protocol.message.Response;
import net.vectora.protocol.message.ServiceReady;
import net.vectora.protocol.message.ServiceStatus;

/**
 * State machine of one connection: waiting for {@code Hello}, then authenticated, then closing.
 *
 * <p>Before authentication only {@code Hello} is accepted, within the handshake timeout. Any
 * other message, a second {@code Hello}, a message meant for the other direction or a report
 * about another service is a protocol violation: the cloud sends
 * {@code Disconnect(PROTOCOL_ERROR)} and closes. The token is never logged.
 */
final class ConnectionHandler extends SimpleChannelInboundHandler<Envelope> {

    private static final System.Logger LOG = System.getLogger(ConnectionHandler.class.getName());

    /** Time a closing connection gets to flush its last message before it is cut. */
    static final long CLOSE_GRACE_MILLIS = 1_000;

    private final CloudNetworkServer server;
    private final EnvelopeDecoder decoder;
    private ScheduledFuture<?> handshakeTimer;
    private ServiceSession session;
    private boolean closing;
    private Disconnect.Reason closeReason;

    ConnectionHandler(CloudNetworkServer server, EnvelopeDecoder decoder) {
        super(Envelope.class);
        this.server = server;
        this.decoder = decoder;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        long timeout = server.settings().handshakeTimeout().toMillis();
        handshakeTimer = ctx.executor().schedule(() -> {
            if (session == null && !closing) {
                LOG.log(Level.INFO, "Closing connection from {0}: no Hello within {1} ms", remote(ctx),
                        String.valueOf(timeout));
                closeWith(ctx, new Disconnect(Disconnect.Reason.TIMEOUT), Disconnect.Reason.TIMEOUT);
            }
        }, timeout, TimeUnit.MILLISECONDS);
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (handshakeTimer != null) {
            handshakeTimer.cancel(false);
        }
        if (session != null) {
            server.sessionClosed(session, closeReason);
        }
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Envelope envelope) {
        // A connection the cloud is closing (replaced by a newer one, disconnected, shut down) may
        // still have frames in flight; they no longer speak for the service.
        if (closing || session != null && session.closedByCloud() != null) {
            return;
        }
        if (session == null) {
            handshake(ctx, envelope.message());
        } else {
            authenticated(ctx, envelope);
        }
    }

    private void handshake(ChannelHandlerContext ctx, Message message) {
        if (!(message instanceof Hello hello)) {
            violation(ctx, "expected Hello, got " + message.getClass().getSimpleName());
            return;
        }
        PeerId peer = hello.peer();
        AuthResult.Outcome outcome;
        if (!hello.version().isCompatibleWith(Protocol.PROTOCOL_VERSION)) {
            outcome = AuthResult.Outcome.INCOMPATIBLE_VERSION;
        } else if (!(peer instanceof ServiceId)) {
            // Cluster nodes arrive with PR 20, over mTLS.
            outcome = AuthResult.Outcome.UNKNOWN_PEER;
        } else {
            outcome = server.authenticate(peer, hello.token());
        }
        if (outcome != AuthResult.Outcome.ACCEPTED) {
            LOG.log(Level.WARNING, "Refused {0} {1} from {2}: {3}", peer.role(), peer.value(), remote(ctx), outcome);
            closeWith(ctx, new AuthResult(outcome, Protocol.PROTOCOL_VERSION), Disconnect.Reason.AUTH_FAILED);
            return;
        }
        handshakeTimer.cancel(false);
        decoder.authenticated();
        // The lower minor version of both sides: the cloud sends nothing the peer would not know.
        session = server.newSession((ServiceId) peer, ctx.channel(), hello.version().negotiate(Protocol.PROTOCOL_VERSION));
        // Registered before the answer goes out, so the cloud can send to the service as soon as
        // the service knows it is accepted.
        server.register(session);
        ctx.writeAndFlush(Envelope.of(new AuthResult(AuthResult.Outcome.ACCEPTED, Protocol.PROTOCOL_VERSION)));
        LOG.log(Level.INFO, "Service {0} connected from {1}", peer.value(), remote(ctx));
        server.sessionOpened(session);
    }

    private void authenticated(ChannelHandlerContext ctx, Envelope envelope) {
        Message message = envelope.message();
        switch (message) {
            case Ping ping -> ctx.writeAndFlush(Envelope.of(new Pong(ping.nonce())));
            case Disconnect disconnect -> {
                closing = true;
                closeReason = disconnect.reason();
                ctx.close();
            }
            case ServiceReady ready -> {
                if (ownReport(ctx, ready.service())) {
                    server.serviceReady(session);
                }
            }
            case ServiceStatus status -> {
                if (ownReport(ctx, status.service())) {
                    server.serviceStatus(status);
                }
            }
            case PlayerJoin join -> server.messageReceived(session, join);
            case PlayerQuit quit -> server.messageReceived(session, quit);
            case PlayerSwitch change -> server.messageReceived(session, change);
            // A backend reports only its own kicks; whether the sender is a backend at all is the
            // node's business.
            case NetworkKick kick -> {
                if (ownReport(ctx, kick.server())) {
                    server.messageReceived(session, kick);
                }
            }
            case ChannelMessage channelMessage -> server.channelMessage(session, channelMessage);
            case Request request -> server.incomingRequest(session, envelope.requestId(), request);
            case Response response -> session.complete(envelope.requestId(), response);
            default -> {
                // Pong needs nothing: any inbound frame already resets the idle timer. Hello,
                // AuthResult, RegisterServer, UnregisterServer, ServerCapacity and TransferPlayer
                // never come from a service: only the cloud moves players.
                if (!(message instanceof Pong)) {
                    violation(ctx, message.getClass().getSimpleName() + " from a peer");
                }
            }
        }
    }

    /** A service may only report about itself. */
    private boolean ownReport(ChannelHandlerContext ctx, ServiceId reported) {
        if (reported.equals(session.service())) {
            return true;
        }
        violation(ctx, "report for another service");
        return false;
    }

    /**
     * Backpressure: while more than the high water mark is queued for the peer, the cloud stops
     * reading from it. A peer that sends pings or requests but never reads the answers can
     * therefore not make the cloud buffer without limit; if it stays stuck, the idle timeout ends
     * the connection.
     */
    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (!closing && (session == null || session.closedByCloud() == null)) {
            ctx.channel().config().setAutoRead(ctx.channel().isWritable());
        }
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof IdleStateEvent idle) {
            if (idle.state() == IdleState.READER_IDLE && !closing) {
                LOG.log(Level.INFO, "Closing connection {0}: idle timeout", describe(ctx));
                closeWith(ctx, new Disconnect(Disconnect.Reason.TIMEOUT), Disconnect.Reason.TIMEOUT);
            } else if (idle.state() == IdleState.WRITER_IDLE && session != null && !closing) {
                ctx.writeAndFlush(Envelope.of(new Ping(ThreadLocalRandom.current().nextLong())));
            }
            return;
        }
        super.userEventTriggered(ctx, event);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Throwable root = cause instanceof DecoderException && cause.getCause() != null ? cause.getCause() : cause;
        if (closing) {
            return;
        }
        if (root instanceof IncompatibleVersionException incompatible) {
            LOG.log(Level.WARNING, "Closing connection {0}: protocol version {1} is not compatible with {2}",
                    describe(ctx), incompatible.remoteVersion(), Protocol.PROTOCOL_VERSION);
            Message answer = session == null
                    ? new AuthResult(AuthResult.Outcome.INCOMPATIBLE_VERSION, Protocol.PROTOCOL_VERSION)
                    : new Disconnect(Disconnect.Reason.INCOMPATIBLE_VERSION);
            closeWith(ctx, answer, Disconnect.Reason.INCOMPATIBLE_VERSION);
        } else if (root instanceof ProtocolException protocol) {
            violation(ctx, protocol.getMessage());
        } else if (root instanceof IOException) {
            LOG.log(Level.DEBUG, "Connection {0} failed: {1}", describe(ctx), root.getMessage());
            ctx.close();
        } else {
            LOG.log(Level.ERROR, "Unexpected error on connection " + describe(ctx), root);
            closeWith(ctx, new Disconnect(Disconnect.Reason.PROTOCOL_ERROR), Disconnect.Reason.PROTOCOL_ERROR);
        }
    }

    private void violation(ChannelHandlerContext ctx, String what) {
        // Protocol exception messages never contain the offending bytes, see docs/cloud/protocol.md.
        LOG.log(Level.WARNING, "Closing connection {0}: protocol violation: {1}", describe(ctx), what);
        closeWith(ctx, new Disconnect(Disconnect.Reason.PROTOCOL_ERROR), Disconnect.Reason.PROTOCOL_ERROR);
    }

    /**
     * Sends a last message and closes. Reading stops at once; if the peer does not take the
     * message within a second (it may not be reading at all), the connection is cut anyway.
     */
    private void closeWith(ChannelHandlerContext ctx, Message last, Disconnect.Reason reason) {
        if (closing) {
            return;
        }
        closing = true;
        closeReason = reason;
        ctx.channel().config().setAutoRead(false);
        ctx.writeAndFlush(Envelope.of(last)).addListener(ChannelFutureListener.CLOSE);
        ctx.executor().schedule(() -> {
            ctx.close();
        }, CLOSE_GRACE_MILLIS, TimeUnit.MILLISECONDS);
    }

    private String describe(ChannelHandlerContext ctx) {
        return session == null ? "from " + remote(ctx) : "of service " + session.service().value();
    }

    private static Object remote(ChannelHandlerContext ctx) {
        return ctx.channel().remoteAddress() instanceof InetSocketAddress address
                ? address.getAddress().getHostAddress() + ":" + address.getPort()
                : ctx.channel().remoteAddress();
    }
}
