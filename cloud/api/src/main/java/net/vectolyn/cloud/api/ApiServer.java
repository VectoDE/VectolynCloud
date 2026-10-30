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
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
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

    /** Name of the idle handler in the pipeline. */
    static final String IDLE = "vectolyn-api-idle";

    private static final System.Logger LOG = System.getLogger(ApiServer.class.getName());
    private static final WriteBufferWaterMark WATER_MARK = new WriteBufferWaterMark(256 * 1024, 1024 * 1024);

    private final ApiSettings settings;
    private final ApiTokens tokens;
    private final ApiBackend backend;
    private final Router<ApiRoutes.Handler> router;
    private final RateLimiter<String> addressLimiter;
    private final RateLimiter<String> tokenLimiter;
    private final RateLimiter<String> authFailureLimiter;
    private final Semaphore requestSlots;
    private final EventHub events;
    /** Guards {@link #connections} and {@link #openConnections}; never the server monitor (event loops take it). */
    private final Object connectionLock = new Object();
    private final Map<String, Integer> connections = new HashMap<>();
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("vectolyn-api-", 0).factory());

    private volatile boolean shuttingDown;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private SslContext sslContext;
    private int openConnections;
    private boolean closed;

    private ApiServer(Builder builder) {
        this.settings = builder.settings;
        this.tokens = builder.tokens;
        this.backend = builder.backend;
        ApiLimits limits = settings.limits();
        this.router = new ApiRoutes(builder.config, backend, () -> shuttingDown).router();
        this.addressLimiter = new RateLimiter<>(limits.burstPerIp(), limits.requestsPerMinutePerIp(), builder.nanoTime);
        this.tokenLimiter = new RateLimiter<>(limits.burstPerToken(), limits.requestsPerMinutePerToken(), builder.nanoTime);
        this.authFailureLimiter = new RateLimiter<>(limits.authFailuresPerMinute(), limits.authFailuresPerMinute(),
                builder.nanoTime);
        this.requestSlots = new Semaphore(limits.maxConcurrentRequests());
        this.events = new EventHub(this::redact, limits.maxWebSockets());
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
            } catch (IOException e) {
                throw new ApiStartException(e.getMessage());
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
            throw new ApiStartException("api.bind cannot be resolved.");
        }
        if (settings.tls().isEmpty() && !address.isLoopbackAddress()) {
            throw new ApiStartException("The API listens on a non-loopback address (api.bind) and therefore needs TLS: "
                    + "set api.tls.enabled: true and api.tls.keystore.");
        }
        return address;
    }

    private void initConnection(SocketChannel ch) {
        String client = ClientAddress.key(ch.remoteAddress());
        if (!acquireConnection(client)) {
            ch.close();
            return;
        }
        ch.closeFuture().addListener(f -> releaseConnection(client));
        ChannelPipeline p = ch.pipeline();
        if (sslContext != null) {
            p.addLast("vectolyn-api-tls", sslContext.newHandler(ch.alloc()));
        }
        p.addLast("vectolyn-api-http", new HttpServerCodec(new HttpDecoderConfig()
                .setMaxInitialLineLength(4096)
                .setMaxHeaderSize(8192)
                .setMaxChunkSize(8192)));
        int idle = settings.limits().idleTimeoutSeconds();
        p.addLast(IDLE, new IdleStateHandler(idle, 0, 0, TimeUnit.SECONDS));
        p.addLast("vectolyn-api-handler", new ApiHttpHandler(this));
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
        events.closeAll();
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
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

    String redact(String text) {
        return backend.redact(text);
    }

    /** Builder of {@link ApiServer}. */
    public static final class Builder {

        private final ConfigService config;
        private final ApiBackend backend;
        private final ApiTokens tokens;
        private ApiSettings settings;
        private LongSupplier nanoTime = System::nanoTime;

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
