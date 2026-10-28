package net.vectora.cloud.network;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.vectora.protocol.AuthToken;
import net.vectora.protocol.ChannelName;
import net.vectora.protocol.PeerId;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.AuthResult;
import net.vectora.protocol.message.ChannelMessage;
import net.vectora.protocol.message.Disconnect;
import net.vectora.protocol.message.Envelope;
import net.vectora.protocol.message.Hello;
import net.vectora.protocol.message.Message;
import net.vectora.protocol.message.MessageCodec;
import net.vectora.protocol.message.Request;
import net.vectora.protocol.message.Response;
import net.vectora.protocol.message.ServiceStatus;

/**
 * Netty server of the cloud channel: the services the node starts connect here with their token.
 *
 * <p>What it does:
 * <ul>
 *   <li>Frames and messages with the codec of {@code Vectora-Protocol}; 4 KiB frame limit until
 *       a peer has authenticated, 1 MiB afterwards.</li>
 *   <li>{@code Hello} with the service token, checked by a {@link ServiceAuthenticator} in
 *       constant time; the token is never logged.</li>
 *   <li>Handshake timeout, idle timeout and heartbeat ({@code Ping} when nothing else was sent).</li>
 *   <li>Limits for open connections in total and per IP address.</li>
 *   <li>{@code Disconnect(PROTOCOL_ERROR)} and close on any protocol violation.</li>
 *   <li>Requests in both directions with request ids and timeouts, and channel messages: a
 *       message a service publishes goes to the cloud's {@link #subscribe subscribers} of that
 *       channel and to every other connected service.</li>
 * </ul>
 *
 * <p>The node wires it to its service core through {@link ServiceListener} and
 * {@link ServiceAuthenticator}. Callbacks run on network threads. All public methods are
 * thread-safe. See {@code docs/cloud/network.md}.
 */
public final class CloudNetworkServer implements AutoCloseable {

    /** Name of the frame decoder in the pipeline; a later {@code SslHandler} goes in front of it. */
    public static final String FRAME_DECODER = "vectora-frame-decoder";

    /** Name of the frame encoder in the pipeline. */
    public static final String FRAME_ENCODER = "vectora-frame-encoder";

    /** Name of the idle handler in the pipeline. */
    public static final String IDLE_HANDLER = "vectora-idle";

    /** Name of the connection handler in the pipeline. */
    public static final String CONNECTION_HANDLER = "vectora-connection";

    private static final System.Logger LOG = System.getLogger(CloudNetworkServer.class.getName());
    private static final byte[] EMPTY = new byte[0];

    /** A slow service is skipped for channel messages once this much is queued for it. */
    private static final WriteBufferWaterMark WATER_MARK = new WriteBufferWaterMark(512 * 1024, 2 * 1024 * 1024);

    private final NetworkSettings settings;
    private final ServiceAuthenticator authenticator;
    private final ServiceListener listener;
    private final PipelineCustomizer customizer;
    private final MessageCodec codec = MessageCodec.standard();
    private final EnvelopeEncoder encoder = new EnvelopeEncoder(codec);
    private final ConnectionLimiter limiter;
    private final Map<ServiceId, ServiceSession> sessions = new ConcurrentHashMap<>();
    private final Map<ChannelName, RequestHandler> requestHandlers = new ConcurrentHashMap<>();
    private final Map<ChannelName, List<ChannelSubscriber>> subscribers = new ConcurrentHashMap<>();
    private final AtomicLong droppedChannelMessages = new AtomicLong();

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private boolean closed;

    private CloudNetworkServer(Builder builder) {
        this.settings = builder.settings;
        this.authenticator = builder.authenticator;
        this.listener = builder.listener;
        this.customizer = builder.customizer;
        this.limiter = new ConnectionLimiter(settings.maxConnections(), settings.maxConnectionsPerAddress());
    }

    /** Starts a builder. */
    public static Builder builder(NetworkSettings settings, ServiceAuthenticator authenticator) {
        return new Builder(settings, authenticator);
    }

    /**
     * Binds the listener and returns the address it listens on.
     *
     * @throws IOException if the address cannot be bound (port in use, unknown host)
     * @throws IllegalStateException if the server was started or closed before
     */
    public synchronized InetSocketAddress start() throws IOException {
        if (closed || serverChannel != null) {
            throw new IllegalStateException("the server can be started only once");
        }
        InetSocketAddress bind = new InetSocketAddress(settings.bindHost(), settings.port());
        if (bind.isUnresolved()) {
            throw new IOException("cannot resolve bind host " + settings.bindHost());
        }
        bossGroup = new MultiThreadIoEventLoopGroup(1, new DefaultThreadFactory("vectora-network-accept"),
                NioIoHandler.newFactory());
        int workers = Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 4));
        workerGroup = new MultiThreadIoEventLoopGroup(workers, new DefaultThreadFactory("vectora-network"),
                NioIoHandler.newFactory());
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WATER_MARK)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        acceptConnection(channel);
                    }
                });
        ChannelFuture bound = bootstrap.bind(bind).awaitUninterruptibly();
        if (!bound.isSuccess()) {
            shutDownGroups();
            Throwable cause = bound.cause();
            throw cause instanceof IOException io ? io : new IOException("cannot bind " + bind, cause);
        }
        serverChannel = bound.channel();
        InetSocketAddress local = (InetSocketAddress) serverChannel.localAddress();
        if (local.getAddress().isLoopbackAddress()) {
            LOG.log(Level.INFO, "Cloud channel listens on {0}:{1} (loopback only)",
                    local.getAddress().getHostAddress(), String.valueOf(local.getPort()));
        } else {
            LOG.log(Level.WARNING, "Cloud channel listens on {0}:{1}, which is not a loopback address. "
                    + "The channel has no TLS yet: tokens and messages travel unencrypted. Use a firewall.",
                    local.getAddress().getHostAddress(), String.valueOf(local.getPort()));
        }
        return local;
    }

    /** Returns the address the server listens on, or {@code null} before {@link #start()}. */
    public synchronized InetSocketAddress localAddress() {
        return serverChannel == null ? null : (InetSocketAddress) serverChannel.localAddress();
    }

    /** Returns the settings. */
    public NetworkSettings settings() {
        return settings;
    }

    /**
     * Sends {@code Disconnect(SHUTDOWN)} to every service, stops listening and releases the
     * threads. Waits at most a few seconds. Safe to call more than once.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (serverChannel != null) {
            // Stop accepting first, so no new service slips in while the others are told to go.
            serverChannel.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
        }
        List<ChannelFuture> goodbyes = new ArrayList<>();
        for (ServiceSession session : sessions.values()) {
            goodbyes.add(session.disconnect(Disconnect.Reason.SHUTDOWN));
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        for (ChannelFuture goodbye : goodbyes) {
            goodbye.awaitUninterruptibly(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        }
        shutDownGroups();
    }

    /** Returns the services that are connected and authenticated right now. */
    public Set<ServiceId> connectedServices() {
        return Set.copyOf(sessions.keySet());
    }

    /** Returns whether {@code service} has an open, authenticated connection. */
    public boolean isConnected(ServiceId service) {
        return sessions.containsKey(service);
    }

    /** Returns the number of open connections, authenticated or not. */
    public int openConnections() {
        return limiter.open();
    }

    /** Returns how many channel messages were skipped because a service did not read fast enough. */
    public long droppedChannelMessages() {
        return droppedChannelMessages.get();
    }

    /**
     * Sends a message to one service, for example {@code RegisterServer} to a proxy.
     *
     * @return {@code false} if the service is not connected
     * @throws IllegalArgumentException for {@code Hello}, {@code AuthResult}, {@code Request} and
     *     {@code Response}, which only the connection itself or {@link #request} send
     */
    public boolean send(ServiceId service, Message message) {
        Objects.requireNonNull(message, "message");
        if (message instanceof Hello || message instanceof AuthResult || message.requiresRequestId()) {
            throw new IllegalArgumentException(message.getClass().getSimpleName() + " cannot be sent with send()");
        }
        ServiceSession session = sessions.get(service);
        if (session == null) {
            return false;
        }
        session.channel().writeAndFlush(Envelope.of(message));
        return true;
    }

    /** Publishes a channel message from the cloud to every connected service. */
    public void publish(ChannelMessage message) {
        fanOut(null, message);
    }

    /**
     * Sends a request to a service. The future completes with the service's response, or fails
     * with {@link java.util.concurrent.TimeoutException} after the request timeout, or with
     * {@link ServiceNotConnectedException}.
     */
    public CompletableFuture<Response> request(ServiceId service, Request request) {
        Objects.requireNonNull(request, "request");
        ServiceSession session = sessions.get(service);
        if (session == null) {
            return CompletableFuture.failedFuture(new ServiceNotConnectedException(service + " is not connected"));
        }
        return session.request(request, settings.requestTimeout());
    }

    /**
     * Registers the handler for requests on {@code channel}; one handler per channel.
     *
     * @return closing the registration removes the handler again
     * @throws IllegalStateException if the channel already has a handler
     */
    public Registration handleRequests(ChannelName channel, RequestHandler handler) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(handler, "handler");
        if (requestHandlers.putIfAbsent(channel, handler) != null) {
            throw new IllegalStateException("channel " + channel + " already has a request handler");
        }
        return () -> requestHandlers.remove(channel, handler);
    }

    /**
     * Subscribes to channel messages that services publish on {@code channel}.
     *
     * @return closing the registration ends the subscription
     */
    public Registration subscribe(ChannelName channel, ChannelSubscriber subscriber) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(subscriber, "subscriber");
        subscribers.computeIfAbsent(channel, key -> new CopyOnWriteArrayList<>()).add(subscriber);
        return () -> subscribers.computeIfPresent(channel, (key, list) -> {
            list.remove(subscriber);
            return list.isEmpty() ? null : list;
        });
    }

    /** Closes the connection of {@code service} with the given reason, if it has one. */
    public void disconnect(ServiceId service, Disconnect.Reason reason) {
        ServiceSession session = sessions.get(service);
        if (session != null) {
            session.disconnect(reason);
        }
    }

    // ---- connection side, called by ConnectionHandler on the channel's event loop ----

    /** Applies the limits and builds the pipeline of a new connection. */
    void acceptConnection(Channel channel) {
        InetSocketAddress remote = (InetSocketAddress) channel.remoteAddress();
        if (remote == null || !limiter.tryAcquire(remote.getAddress())) {
            LOG.log(Level.WARNING, "Refusing connection from {0}: connection limit reached",
                    remote == null ? "an unknown address" : remote.getAddress().getHostAddress());
            channel.close();
            return;
        }
        channel.closeFuture().addListener(closed -> limiter.release(remote.getAddress()));
        initPipeline(channel);
    }

    /** Adds the cloud's handlers to {@code channel}; also used by the tests with an embedded channel. */
    void initPipeline(Channel channel) {
        customizer.customize(channel);
        EnvelopeDecoder decoder = new EnvelopeDecoder(codec);
        channel.pipeline()
                .addLast(FRAME_DECODER, decoder)
                .addLast(FRAME_ENCODER, encoder)
                .addLast(IDLE_HANDLER, new IdleStateHandler(settings.idleTimeout().toMillis(),
                        settings.pingInterval().toMillis(), 0, TimeUnit.MILLISECONDS))
                .addLast(CONNECTION_HANDLER, new ConnectionHandler(this, decoder));
    }

    AuthResult.Outcome authenticate(PeerId peer, AuthToken token) {
        try {
            return Objects.requireNonNull(authenticator.authenticate(peer, token), "outcome");
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Authenticator failed for " + peer.role() + " " + peer.value(), e);
            return AuthResult.Outcome.UNKNOWN_PEER;
        }
    }

    ServiceSession newSession(ServiceId service, Channel channel) {
        return new ServiceSession(service, channel, settings.maxPendingRequests());
    }

    /** Registers an authenticated session; an older connection of the same service is replaced. */
    void register(ServiceSession session) {
        ServiceSession previous = sessions.put(session.service(), session);
        if (previous != null) {
            previous.markReplaced();
            LOG.log(Level.INFO, "Service {0} reconnected, closing its older connection",
                    session.service().value());
            previous.disconnect(Disconnect.Reason.REPLACED);
        }
    }

    /** Tells the listener about a session after its {@code AuthResult} has been written. */
    void sessionOpened(ServiceSession session) {
        notifyListener(() -> listener.serviceConnected(session.service(), session.remoteAddress()));
    }

    void sessionClosed(ServiceSession session, Disconnect.Reason handlerReason) {
        session.failAll();
        Disconnect.Reason reason = handlerReason != null ? handlerReason : session.closedByCloud();
        boolean current = sessions.remove(session.service(), session);
        if (current && !session.isReplaced()) {
            LOG.log(Level.INFO, "Service {0} disconnected ({1})", session.service().value(),
                    reason == null ? "connection lost" : reason);
            notifyListener(() -> listener.serviceDisconnected(session.service(), reason));
        }
    }

    void serviceReady(ServiceSession session) {
        notifyListener(() -> listener.serviceReady(session.service()));
    }

    void serviceStatus(ServiceStatus status) {
        notifyListener(() -> listener.serviceStatus(status));
    }

    void messageReceived(ServiceSession session, Message message) {
        notifyListener(() -> listener.messageReceived(session.service(), message));
    }

    void channelMessage(ServiceSession from, ChannelMessage message) {
        List<ChannelSubscriber> local = subscribers.get(message.channel());
        if (local != null) {
            for (ChannelSubscriber subscriber : local) {
                notifyListener(() -> subscriber.received(from.service(), message));
            }
        }
        fanOut(from, message);
    }

    void incomingRequest(ServiceSession session, int requestId, Request request) {
        if (!session.tryStartIncoming()) {
            session.channel().writeAndFlush(new Envelope(requestId, new Response(Response.Status.DENIED, EMPTY)));
            return;
        }
        RequestHandler handler = requestHandlers.get(request.channel());
        if (handler == null) {
            session.endIncoming();
            session.channel().writeAndFlush(new Envelope(requestId, new Response(Response.Status.NO_HANDLER, EMPTY)));
            return;
        }
        CompletableFuture<Response> result = new CompletableFuture<>();
        try {
            CompletionStage<Response> stage = handler.handle(session.service(), request);
            if (stage == null) {
                result.completeExceptionally(new NullPointerException("handler returned no stage"));
            } else {
                stage.whenComplete((response, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                    } else {
                        result.complete(response);
                    }
                });
            }
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
        }
        result.orTimeout(settings.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((response, error) -> {
                    session.endIncoming();
                    Response answer = response;
                    if (error != null || response == null) {
                        LOG.log(Level.WARNING, "Request handler for channel " + request.channel() + " failed",
                                error);
                        answer = new Response(Response.Status.ERROR, EMPTY);
                    }
                    if (session.channel().isActive()) {
                        session.channel().writeAndFlush(new Envelope(requestId, answer));
                    }
                });
    }

    private void fanOut(ServiceSession from, ChannelMessage message) {
        byte[] frame = codec.encode(Envelope.of(message));
        for (ServiceSession target : sessions.values()) {
            if (target == from) {
                continue;
            }
            Channel channel = target.channel();
            if (!channel.isWritable()) {
                long dropped = droppedChannelMessages.incrementAndGet();
                LOG.log(Level.DEBUG, "Skipped a message on {0} for {1}: too much queued ({2} so far)",
                        message.channel(), target.service().value(), String.valueOf(dropped));
                continue;
            }
            // Encoded once; each write gets its own buffer that shares the array.
            channel.writeAndFlush(Unpooled.wrappedBuffer(frame));
        }
    }

    private static void notifyListener(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Listener of the cloud network failed", e);
        }
    }

    private void shutDownGroups() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
    }

    /** Handle of a registered handler or subscription; {@link #close()} removes it. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {

        /** Removes the handler or subscription. Safe to call more than once. */
        @Override
        void close();
    }

    /** Builds a {@link CloudNetworkServer}. */
    public static final class Builder {

        private final NetworkSettings settings;
        private final ServiceAuthenticator authenticator;
        private ServiceListener listener = ServiceListener.NONE;
        private PipelineCustomizer customizer = PipelineCustomizer.NONE;

        private Builder(NetworkSettings settings, ServiceAuthenticator authenticator) {
            this.settings = Objects.requireNonNull(settings, "settings");
            this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        }

        /** Sets the listener for service events. */
        public Builder listener(ServiceListener value) {
            this.listener = Objects.requireNonNull(value, "listener");
            return this;
        }

        /** Sets the pipeline hook, for example to add TLS. */
        public Builder pipelineCustomizer(PipelineCustomizer value) {
            this.customizer = Objects.requireNonNull(value, "customizer");
            return this;
        }

        /** Builds the server; it does not listen before {@link CloudNetworkServer#start()}. */
        public CloudNetworkServer build() {
            return new CloudNetworkServer(this);
        }
    }
}
