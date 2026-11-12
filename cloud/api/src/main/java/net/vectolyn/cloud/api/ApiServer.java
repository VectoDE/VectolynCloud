// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import net.vectolyn.cloud.api.auth.ApiTokens;
import net.vectolyn.cloud.api.auth.RateLimiter;
import net.vectolyn.cloud.api.backend.ApiBackend;
import net.vectolyn.cloud.api.event.ApiEvent;
import net.vectolyn.cloud.api.http.ApiRoutes;
import net.vectolyn.cloud.api.http.Router;
import net.vectolyn.cloud.config.ConfigService;
import org.jspecify.annotations.Nullable;

/**
 * The REST and WebSocket API of the node (Netty, HTTP/1.1, JSON).
 *
 * <p>Security model, see {@code docs/cloud/api.md}:
 * <ul>
 *   <li>Every request needs a token ({@code Authorization: Bearer vct_...}) with the scope of its
 *       endpoint; without one everything is 401, even unknown paths.</li>
 *   <li>Rate limits per client address and per token, and a separate limit for wrong tokens per
 *       address against guessing.</li>
 *   <li>Plain HTTP only on a loopback address. Anything else needs TLS ({@link ApiTls}); the server
 *       refuses to start otherwise, whatever the configuration says.</li>
 *   <li>CORS is off unless {@code api.cors-origins} lists the dashboard's origin.</li>
 *   <li>Bodies at most {@link ApiLimits#maxBodyBytes()}, read as strictly as the YAML files.</li>
 *   <li>Every change goes through {@code ConfigService.apply}; while the node shuts down, starts,
 *       restarts, reloads and backups are refused.</li>
 * </ul>
 *
 * <p>cloud-node builds it once with {@link #builder}, calls {@link #start()} after the console,
 * hands every service event to {@link #publish}, and calls {@link #beginShutdown()} at the start
 * of its own shutdown and {@link #close()} at the end.
 */
public final class ApiServer implements AutoCloseable {

    /** How often a {@code metrics} event is sent while a stream wants one. */
    static final int METRICS_SECONDS = 10;

    /** How often open event streams are checked for an expired token. */
    static final int EXPIRY_CHECK_SECONDS = 15;

    /** Name of the idle handler in the pipeline. */
    static final String IDLE = "vectolyn-api-idle";

    private static final System.Logger LOG = System.getLogger(ApiServer.class.getName());
    private static final WriteBufferWaterMark WATER_MARK = new WriteBufferWaterMark(256 * 1024, 1024 * 1024);

    private final ApiSettings settings;
    private final ApiTokens tokens;
    private final ApiBackend backend;
    private final Router<ApiRoutes.Handler> router;
    private final RateLimiter<String> addressLimiter;
    private final LongSupplier nanoTime;
    private final RateLimiter<String> tokenLimiter;
    private final RateLimiter<String> authFailureLimiter;
    private final Semaphore requestSlots;
    private final EventHub events;
    private final GraphQlApi graphQl;
    private final GrpcService grpc;
    /** Guards {@link #connections} and {@link #openConnections}; never the server monitor (event loops take it). */
    private final Object connectionLock = new Object();
    private final Map<String, Integer> connections = new HashMap<>();
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("vectolyn-api-", 0).factory());

    private final long expiryCheckMillis;
    private final long metricsMillis;
    private final ApiLogHandler logHandler;
    private volatile boolean shuttingDown;
    private @Nullable EventLoopGroup bossGroup;
    private @Nullable EventLoopGroup workerGroup;
    private @Nullable Channel serverChannel;
    private @Nullable Channel grpcChannel;
    private volatile @Nullable SslContext sslContext;
    private volatile @Nullable SslContext grpcSslContext;
    private int openConnections;
    private boolean closed;

    private ApiServer(Builder builder) {
        this.settings = Objects.requireNonNull(builder.settings, "settings");
        this.tokens = builder.tokens;
        this.backend = builder.backend;
        this.nanoTime = builder.nanoTime;
        ApiLimits limits = settings.limits();
        this.router = new ApiRoutes(builder.config, backend, () -> shuttingDown, tokens)
                .graphql(q -> graphQl().handle(q)).router();
        this.addressLimiter = new RateLimiter<>(limits.burstPerIp(), limits.requestsPerMinutePerIp(), builder.nanoTime);
        this.tokenLimiter = new RateLimiter<>(limits.burstPerToken(), limits.requestsPerMinutePerToken(), builder.nanoTime);
        this.authFailureLimiter = new RateLimiter<>(limits.authFailuresPerMinute(), limits.authFailuresPerMinute(),
                builder.nanoTime);
        this.requestSlots = new Semaphore(limits.maxConcurrentRequests());
        this.events = new EventHub(this::redact, limits.maxWebSockets());
        this.graphQl = new GraphQlApi(router, e -> ApiHttpHandler.toApiException(e, this::redact),
                tokenLimiter::tryAcquire, settings.graphqlIntrospection());
        this.grpc = new GrpcService(new RouteCaller(router, e -> ApiHttpHandler.toApiException(e, this::redact)));
        this.expiryCheckMillis = builder.expiryCheckMillis;
        this.metricsMillis = builder.metricsMillis;
        this.logHandler = new ApiLogHandler(this::publish, events::anyWants);
        tokens.onRevoke(token -> events.closeToken(token.id()));
    }

    /**
     * Starts a builder.
     *
     * @param config  the configuration layer of the node
     * @param backend services, node status and backups of the node
     * @param tokens  the token store, shared with the {@code api token} console commands
     * @return the builder
     */
    public static Builder builder(ConfigService config, ApiBackend backend, ApiTokens tokens) {
        return new Builder(config, backend, tokens);
    }

    /**
     * Binds the listener.
     *
     * @return the address it listens on
     * @throws ApiStartException if the bind address is not loopback and TLS is off, or the keystore cannot be used
     * @throws IOException       if the address cannot be bound, for example because the port is in use;
     *                           both event loop groups have ended by then
     */
    // The periodic checks run until close() ends the event loop group; their futures are never needed.
    @SuppressWarnings("FutureReturnValueIgnored")
    public synchronized InetSocketAddress start() throws IOException {
        if (closed || serverChannel != null) {
            throw new IllegalStateException("the API server can be started only once");
        }
        InetAddress address = bindAddress(settings);
        if (settings.tls().isPresent()) {
            try {
                sslContext = SslContextBuilder.forServer(settings.tls().get().keyManagers())
                        .sslProvider(SslProvider.JDK)
                        .protocols("TLSv1.3", "TLSv1.2")
                        .build();
                if (settings.grpcPort() != 0) {
                    // gRPC needs HTTP/2 over TLS: ALPN "h2" and the cipher suites HTTP/2 allows.
                    grpcSslContext = SslContextBuilder.forServer(settings.tls().get().keyManagers())
                            .sslProvider(SslProvider.JDK)
                            .protocols("TLSv1.3", "TLSv1.2")
                            .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                            .applicationProtocolConfig(new ApplicationProtocolConfig(
                                    ApplicationProtocolConfig.Protocol.ALPN,
                                    ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                                    ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                                    ApplicationProtocolNames.HTTP_2))
                            .build();
                }
            } catch (IOException e) {
                throw new ApiStartException(Objects.requireNonNullElse(e.getMessage(), e.toString()), e);
            }
        }
        bossGroup = new MultiThreadIoEventLoopGroup(1, new DefaultThreadFactory("vectolyn-api-accept"),
                NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(2, new DefaultThreadFactory("vectolyn-api"),
                NioIoHandler.newFactory());
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 64)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WATER_MARK)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        initConnection(ch);
                    }
                });
        // await() instead of sync(): sync() rethrows a failed bind's BindException unchecked, past
        // a catch for IOException, and the event loop groups would keep running without a listener.
        ChannelFuture binding;
        try {
            binding = bootstrap.bind(new InetSocketAddress(address, settings.port())).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdownGroups();
            throw new IOException("interrupted while binding the API", e);
        } catch (RuntimeException e) {
            shutdownGroups();
            throw new IOException("The API cannot listen on port " + settings.port() + ": " + e.getClass().getSimpleName(), e);
        }
        if (!binding.isSuccess()) {
            shutdownGroups();
            Throwable cause = binding.cause();
            String reason = cause == null ? "unknown reason"
                    : cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            throw new IOException("The API cannot listen on port " + settings.port() + ": " + reason, cause);
        }
        serverChannel = binding.channel();
        if (settings.grpcPort() != 0) {
            ServerBootstrap grpcBootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 64)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WATER_MARK)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            initGrpcConnection(ch);
                        }
                    });
            ChannelFuture grpcBinding;
            try {
                grpcBinding = grpcBootstrap.bind(new InetSocketAddress(address, Math.max(0, settings.grpcPort()))).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                serverChannel.close();
                shutdownGroups();
                throw new IOException("interrupted while binding the gRPC listener", e);
            }
            if (!grpcBinding.isSuccess()) {
                serverChannel.close();
                shutdownGroups();
                Throwable cause = grpcBinding.cause();
                throw new IOException("The API cannot listen for gRPC on port " + settings.grpcPort() + ": "
                        + (cause == null ? "unknown reason" : cause.getClass().getSimpleName()), cause);
            }
            grpcChannel = grpcBinding.channel();
            InetSocketAddress grpcBound = (InetSocketAddress) grpcChannel.localAddress();
            LOG.log(System.Logger.Level.INFO, "API gRPC listening on {0}:{1,number,#} ({2})",
                    grpcBound.getAddress().getHostAddress(), grpcBound.getPort(), grpcSslContext == null ? "h2c" : "h2, TLS");
        }
        // A token that expires while its event stream is open: the stream closes with 1008 within
        // EXPIRY_CHECK_SECONDS, as if the token had been revoked.
        workerGroup.scheduleAtFixedRate(() -> events.closeTokensIf(id -> !tokens.isValid(id)),
                expiryCheckMillis, expiryCheckMillis, TimeUnit.MILLISECONDS);
        // Metrics only while a stream wants them; the status is read on a virtual thread, never on
        // the event loop.
        workerGroup.scheduleAtFixedRate(this::publishMetrics, metricsMillis, metricsMillis, TimeUnit.MILLISECONDS);
        InetSocketAddress bound = (InetSocketAddress) serverChannel.localAddress();
        LOG.log(System.Logger.Level.INFO, "API listening on {0}://{1}:{2,number,#}",
                sslContext == null ? "http" : "https", bound.getAddress().getHostAddress(), bound.getPort());
        settings.tls().ifPresent(tls -> LOG.log(System.Logger.Level.INFO,
                "API certificate SHA-256 fingerprint{0}: {1}", tls.generated() ? " (self-signed)" : "", tls.fingerprint()));
        return bound;
    }

    /**
     * Resolves {@code bind} and enforces the TLS rule: plain HTTP only on a loopback address.
     *
     * @param settings the settings
     * @return the address to bind
     * @throws ApiStartException if the address cannot be resolved, or is not loopback while TLS is off
     */
    static InetAddress bindAddress(ApiSettings settings) throws ApiStartException {
        InetAddress address;
        try {
            address = InetAddress.getByName(settings.bind());
        } catch (UnknownHostException e) {
            throw new ApiStartException("api.bind cannot be resolved.", e);
        }
        if (settings.tls().isEmpty() && !address.isLoopbackAddress()) {
            throw new ApiStartException("The API listens on a non-loopback address (api.bind) and therefore needs TLS: "
                    + "set api.tls.enabled: true and api.tls.keystore.");
        }
        return address;
    }

    // Closing a refused connection is fire-and-forget; there is nobody to report a failure to.
    @SuppressWarnings("FutureReturnValueIgnored")
    private void initConnection(SocketChannel ch) {
        String client = ClientAddress.key(ch.remoteAddress());
        if (!acquireConnection(client)) {
            ch.close();
            return;
        }
        ch.closeFuture().addListener(f -> releaseConnection(client));
        ChannelPipeline p = ch.pipeline();
        SslContext tls = sslContext;
        if (tls != null) {
            p.addLast("vectolyn-api-tls", tls.newHandler(ch.alloc()));
        }
        p.addLast("vectolyn-api-http", new HttpServerCodec(new HttpDecoderConfig()
                .setMaxInitialLineLength(4096)
                .setMaxHeaderSize(8192)
                .setMaxChunkSize(8192)));
        int idle = settings.limits().idleTimeoutSeconds();
        p.addLast(IDLE, new IdleStateHandler(idle, 0, 0, TimeUnit.SECONDS));
        p.addLast("vectolyn-api-handler", new ApiHttpHandler(this));
    }

    /** Most concurrent gRPC calls on one connection. */
    static final int GRPC_MAX_STREAMS = 16;

    /**
     * One gRPC connection: TLS with ALPN {@code h2} (or HTTP/2 with prior knowledge on loopback),
     * then one {@link GrpcStreamHandler} per stream. Same connection limits as the HTTP listener;
     * at most {@value #GRPC_MAX_STREAMS} calls at once, 8 KiB of headers. {@link GrpcConnection}
     * closes the connection when no authenticated call was open for the idle timeout.
     */
    // Closing a refused connection is fire-and-forget; there is nobody to report a failure to.
    @SuppressWarnings("FutureReturnValueIgnored")
    private void initGrpcConnection(SocketChannel ch) {
        String client = ClientAddress.key(ch.remoteAddress());
        if (!acquireConnection(client)) {
            ch.close();
            return;
        }
        ch.closeFuture().addListener(f -> releaseConnection(client));
        ChannelPipeline p = ch.pipeline();
        SslContext tls = grpcSslContext;
        if (tls != null) {
            p.addLast("vectolyn-grpc-tls", tls.newHandler(ch.alloc()));
        }
        p.addLast("vectolyn-grpc-h2", Http2FrameCodecBuilder.forServer()
                .initialSettings(Http2Settings.defaultSettings()
                        .maxConcurrentStreams(GRPC_MAX_STREAMS)
                        .maxHeaderListSize(8192))
                .validateHeaders(true)
                // Netty's defaults, set here so the protection against reset, control-frame and
                // empty-frame floods is visible.
                .decoderEnforceMaxRstFramesPerWindow(200, 30)
                .encoderEnforceMaxQueuedControlFrames(10_000)
                .decoderEnforceMaxConsecutiveEmptyDataFrames(2)
                .build());
        p.addLast("vectolyn-grpc-connection", new GrpcConnection(settings.limits().idleTimeoutSeconds(),
                settings.limits().maxBodyBytes() + 5L, nanoTime));
        p.addLast("vectolyn-grpc-mux", new Http2MultiplexHandler(new ChannelInitializer<Http2StreamChannel>() {
            @Override
            protected void initChannel(Http2StreamChannel stream) {
                stream.pipeline().addLast(new GrpcStreamHandler(ApiServer.this, grpc));
            }
        }));
    }

    private boolean acquireConnection(String client) {
        synchronized (connectionLock) {
            return acquireConnectionLocked(client);
        }
    }

    private boolean acquireConnectionLocked(String client) {
        int fromClient = connections.getOrDefault(client, 0);
        if (openConnections >= settings.limits().maxConnections()
                || fromClient >= settings.limits().maxConnectionsPerIp()) {
            return false;
        }
        openConnections++;
        connections.put(client, fromClient + 1);
        return true;
    }

    private void releaseConnection(String client) {
        synchronized (connectionLock) {
            releaseConnectionLocked(client);
        }
    }

    private void releaseConnectionLocked(String client) {
        Integer fromClient = connections.get(client);
        if (fromClient == null) {
            return;
        }
        openConnections--;
        if (fromClient <= 1) {
            connections.remove(client);
        } else {
            connections.put(client, fromClient - 1);
        }
    }

    /**
     * Sends an event to every matching event stream. Never blocks; may be called from any thread,
     * also before {@link #start()} or after {@link #close()} (then it does nothing).
     *
     * @param event the event
     */
    public void publish(ApiEvent event) {
        events.publish(Objects.requireNonNull(event, "event"));
    }

    private void publishMetrics() {
        if (!events.anyWants("metrics")) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    events.publish(new ApiEvent.Metrics(backend.node().status()));
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.DEBUG, "No metrics event: {0}", e.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException e) {
            // closing
        }
    }

    /**
     * The handler that turns the node's log records into {@code node-log} events. cloud-node adds
     * it to the root logger ({@code java.util.logging.Logger.getLogger("")}) and removes it at
     * shutdown; after {@link #close()} it drops every record.
     *
     * @return the handler
     */
    public java.util.logging.Handler logHandler() {
        return logHandler;
    }

    /**
     * Marks the node as shutting down: starts, restarts, reloads and backups get 503 from now on;
     * reads, stops and commands still work.
     */
    public void beginShutdown() {
        shuttingDown = true;
    }

    /**
     * Whether {@link #beginShutdown()} was called.
     *
     * @return the flag
     */
    public boolean shuttingDown() {
        return shuttingDown;
    }

    /**
     * Number of open event streams.
     *
     * @return the count
     */
    public int openEventStreams() {
        return events.size();
    }

    /** Closes every connection and the listener. Idempotent. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        shuttingDown = true;
        logHandler.close();
        events.closeAll();
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (grpcChannel != null) {
            grpcChannel.close().syncUninterruptibly();
        }
        shutdownGroups();
        executor.shutdownNow();
    }

    /** Shuts both event loop groups down at the same time and waits until their threads have ended. */
    private void shutdownGroups() {
        Future<?> boss = bossGroup == null ? null : bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        Future<?> workers = workerGroup == null ? null : workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        if (boss != null) {
            boss.syncUninterruptibly();
        }
        if (workers != null) {
            workers.syncUninterruptibly();
        }
        bossGroup = null;
        workerGroup = null;
    }

    ApiSettings settings() {
        return settings;
    }

    /**
     * The TLS material of the API, which the web panel (cloud/panel) uses for its own listener.
     *
     * @return the material, or empty while the API runs without TLS
     */
    public java.util.Optional<ApiTls.Material> tls() {
        return settings.tls();
    }

    ApiLimits limits() {
        return settings.limits();
    }

    ApiTokens tokens() {
        return tokens;
    }

    Router<ApiRoutes.Handler> router() {
        return router;
    }

    RateLimiter<String> addressLimiter() {
        return addressLimiter;
    }

    RateLimiter<String> tokenLimiter() {
        return tokenLimiter;
    }

    RateLimiter<String> authFailureLimiter() {
        return authFailureLimiter;
    }

    Semaphore requestSlots() {
        return requestSlots;
    }

    ExecutorService executor() {
        return executor;
    }

    EventHub events() {
        return events;
    }

    /**
     * Where the gRPC listener listens.
     *
     * @return the address, or empty while it is off or not started
     */
    public java.util.Optional<InetSocketAddress> grpcAddress() {
        Channel channel = grpcChannel;
        return channel == null ? java.util.Optional.empty()
                : java.util.Optional.of((InetSocketAddress) channel.localAddress());
    }

    GraphQlApi graphQl() {
        return graphQl;
    }

    String redact(String text) {
        return backend.redact(text);
    }

    /** Builder of {@link ApiServer}. */
    public static final class Builder {

        private final ConfigService config;
        private final ApiBackend backend;
        private final ApiTokens tokens;
        private @Nullable ApiSettings settings;
        private LongSupplier nanoTime = System::nanoTime;
        private long expiryCheckMillis = EXPIRY_CHECK_SECONDS * 1000L;
        private long metricsMillis = METRICS_SECONDS * 1000L;

        private Builder(ConfigService config, ApiBackend backend, ApiTokens tokens) {
            this.config = Objects.requireNonNull(config, "config");
            this.backend = Objects.requireNonNull(backend, "backend");
            this.tokens = Objects.requireNonNull(tokens, "tokens");
        }

        /**
         * Where and how to listen; required. {@link ApiSettings#of} builds it from {@code cloud.yml}.
         *
         * @param value the settings
         * @return this builder
         */
        public Builder settings(ApiSettings value) {
            this.settings = Objects.requireNonNull(value, "settings");
            return this;
        }

        /**
         * Monotonic clock of the rate limits (tests).
         *
         * @param value nanoseconds
         * @return this builder
         */
        public Builder nanoTime(LongSupplier value) {
            this.nanoTime = Objects.requireNonNull(value, "nanoTime");
            return this;
        }

        /**
         * How often open event streams are checked for an expired token (tests).
         *
         * @param millis the interval in milliseconds, at least 1
         * @return this builder
         */
        Builder expiryCheckMillis(long millis) {
            if (millis < 1) {
                throw new IllegalArgumentException("millis must be at least 1");
            }
            this.expiryCheckMillis = millis;
            return this;
        }

        /**
         * How often a {@code metrics} event is sent (tests).
         *
         * @param millis the interval in milliseconds, at least 1
         * @return this builder
         */
        Builder metricsMillis(long millis) {
            if (millis < 1) {
                throw new IllegalArgumentException("millis must be at least 1");
            }
            this.metricsMillis = millis;
            return this;
        }

        /**
         * Builds the server without starting it.
         *
         * @return the server
         */
        public ApiServer build() {
            if (settings == null) {
                throw new IllegalStateException("settings are required");
            }
            return new ApiServer(this);
        }
    }
}
