// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel;

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
import io.netty.handler.codec.http.HttpObjectAggregator;
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
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import net.vectolyn.cloud.api.ApiTls;
import net.vectolyn.cloud.api.auth.RateLimiter;
import net.vectolyn.cloud.panel.config.PanelConfigLoader;
import net.vectolyn.cloud.panel.web.WebAssets;

/**
 * The web panel's own HTTP server (Netty, HTTP/1.1), embedded in the node.
 *
 * <p>It serves the statically exported frontend from the jar ({@link WebAssets}) and the panel's
 * JSON endpoints under {@code /api/}. Security model (docs/cloud/panel.md):
 * <ul>
 *   <li>Plain HTTP only on a loopback address; anything else needs the TLS material of the API.
 *       The server refuses to start otherwise.</li>
 *   <li>The {@code Host} header must name this panel (its bind address, {@code localhost} or the
 *       host of {@code public-url}), against DNS rebinding of a panel on loopback.</li>
 *   <li>Rate limit per client address, connection limits, request line, header and body limits,
 *       idle timeout.</li>
 *   <li>Security headers on every answer, a strict CSP with hashes on every page ({@link WebAssets}).</li>
 * </ul>
 */
public final class PanelServer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(PanelServer.class.getName());
    private static final WriteBufferWaterMark WATER_MARK = new WriteBufferWaterMark(256 * 1024, 1024 * 1024);

    /** Name of the idle handler in the pipeline. */
    static final String IDLE = "vectolyn-panel-idle";

    /** Largest request body: a file upload of up to 8 MiB; every other request {@link BodyLimitHandler#SMALL}. */
    static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    /** Most open connections. */
    static final int MAX_CONNECTIONS = 256;
    /** Most open connections of one client address. */
    static final int MAX_CONNECTIONS_PER_IP = 32;
    /** A connection without traffic is closed after this many seconds. */
    static final int IDLE_SECONDS = 120;

    private final PanelConfigLoader.Settings settings;
    private final Optional<ApiTls.Material> tls;
    private final String version;
    private final WebAssets assets;
    private final PanelApi api;
    private final java.util.concurrent.Semaphore uploads = new java.util.concurrent.Semaphore(BodyLimitHandler.UPLOADS);
    private final RateLimiter<String> addressLimiter;
    private final Set<String> allowedHosts;
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("vectolyn-panel-", 0).factory());
    private final Object connectionLock = new Object();
    private final Map<String, Integer> connections = new HashMap<>();
    private int openConnections;
    private SslContext sslContext;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private boolean closed;

    private PanelServer(Builder builder) {
        this.settings = builder.settings;
        this.tls = builder.tls;
        this.version = builder.version;
        this.assets = builder.assets != null ? builder.assets
                : WebAssets.load(PanelServer.class.getClassLoader(), builder.tls.isPresent());
        this.addressLimiter = new RateLimiter<>(120, 600, builder.nanoTime);
        this.api = builder.api;
        if (api != null) {
            api.attach(this);
        }
        this.allowedHosts = allowedHosts(settings);
    }

    /**
     * Starts a builder.
     *
     * @param settings the checked {@code panel.yml}
     * @return the builder
     */
    public static Builder builder(PanelConfigLoader.Settings settings) {
        return new Builder(settings);
    }

    /**
     * Binds the listener.
     *
     * @return the address it listens on
     * @throws IOException if the address is not loopback and TLS is missing, cannot be resolved,
     *                     or cannot be bound; the event loop groups have ended by then
     */
    public synchronized InetSocketAddress start() throws IOException {
        if (closed || serverChannel != null) {
            throw new IllegalStateException("the panel server can be started only once");
        }
        InetAddress address = bindAddress(settings.config().bind(), tls.isPresent());
        if (tls.isPresent()) {
            sslContext = SslContextBuilder.forServer(tls.get().keyManagers())
                    .sslProvider(SslProvider.JDK)
                    .protocols("TLSv1.3", "TLSv1.2")
                    .build();
        }
        bossGroup = new MultiThreadIoEventLoopGroup(1, new DefaultThreadFactory("vectolyn-panel-accept"),
                NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(2, new DefaultThreadFactory("vectolyn-panel"),
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
        ChannelFuture binding;
        try {
            binding = bootstrap.bind(new InetSocketAddress(address, settings.config().port())).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdownGroups();
            throw new IOException("interrupted while binding the panel", e);
        }
        if (!binding.isSuccess()) {
            shutdownGroups();
            Throwable cause = binding.cause();
            String reason = cause == null ? "unknown reason"
                    : cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            throw new IOException("The panel cannot listen on port " + settings.config().port() + ": " + reason, cause);
        }
        serverChannel = binding.channel();
        if (api != null) {
            // Ends live-event streams of sessions that expired, signed out or whose user was disabled.
            workerGroup.next().scheduleAtFixedRate(api::sweepStreams, 15, 15, TimeUnit.SECONDS);
        }
        InetSocketAddress bound = (InetSocketAddress) serverChannel.localAddress();
        LOG.log(System.Logger.Level.INFO, "Panel listening on {0}://{1}:{2,number,#}{3}",
                sslContext == null ? "http" : "https", bound.getAddress().getHostAddress(), bound.getPort(),
                assets.placeholder() ? " (built without the frontend: see docs/cloud/panel.md)" : "");
        return bound;
    }

    /**
     * Resolves the bind address and enforces the TLS rule.
     *
     * @param bind   the configured address
     * @param secure whether TLS material is present
     * @return the address
     * @throws IOException if it cannot be resolved, or is not loopback while TLS is missing
     */
    static InetAddress bindAddress(String bind, boolean secure) throws IOException {
        InetAddress address;
        try {
            address = InetAddress.getByName(bind);
        } catch (UnknownHostException e) {
            throw new IOException("bind in panel.yml cannot be resolved.");
        }
        if (!secure && !address.isLoopbackAddress()) {
            throw new IOException("The panel listens on a non-loopback address and therefore needs TLS: switch on"
                    + " api.tls.enabled in cloud.yml (the panel uses the certificate of the API).");
        }
        return address;
    }

    /**
     * The host names a request may name in {@code Host}: the loopback names, the bind address unless
     * it is a wildcard, and the host of {@code public-url}. An empty set allows every host (wildcard
     * bind without {@code public-url}).
     */
    static Set<String> allowedHosts(PanelConfigLoader.Settings settings) {
        Set<String> hosts = new LinkedHashSet<>(Set.of("localhost", "127.0.0.1", "[::1]"));
        String bind = settings.config().bind().toLowerCase(Locale.ROOT);
        boolean wildcard = bind.equals("0.0.0.0") || bind.equals("::") || bind.equals("[::]");
        if (wildcard && settings.publicUrl().isEmpty()) {
            return Set.of();
        }
        if (!wildcard) {
            hosts.add(bind.contains(":") && !bind.startsWith("[") ? "[" + bind + "]" : bind);
        }
        settings.publicUrl().ifPresent(url -> hosts.add(hostOf(url.substring(url.indexOf("://") + 3))));
        return Set.copyOf(hosts);
    }

    /**
     * The host part of a {@code Host} header value or of an authority, lower case.
     *
     * @param authority {@code host[:port]} or {@code [v6][:port]}
     * @return the host
     */
    static String hostOf(String authority) {
        String value = authority.toLowerCase(Locale.ROOT);
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end < 0 ? value : value.substring(0, end + 1);
        }
        int colon = value.indexOf(':');
        return colon < 0 ? value : value.substring(0, colon);
    }

    private void initConnection(SocketChannel ch) {
        String client = clientKey(ch.remoteAddress());
        if (!acquireConnection(client)) {
            ch.close();
            return;
        }
        ch.closeFuture().addListener(f -> releaseConnection(client));
        ChannelPipeline p = ch.pipeline();
        if (sslContext != null) {
            p.addLast("vectolyn-panel-tls", sslContext.newHandler(ch.alloc()));
        }
        p.addLast("vectolyn-panel-http", new HttpServerCodec(new HttpDecoderConfig()
                .setMaxInitialLineLength(4096)
                .setMaxHeaderSize(8192)
                .setMaxChunkSize(8192)));
        p.addLast(IDLE, new IdleStateHandler(IDLE_SECONDS, 0, 0, TimeUnit.SECONDS));
        p.addLast("vectolyn-panel-limit", new BodyLimitHandler(uploads, secure()));
        p.addLast("vectolyn-panel-body", new HttpObjectAggregator(MAX_BODY_BYTES, true));
        p.addLast("vectolyn-panel-handler", new PanelHttpHandler(this));
    }

    /**
     * The key of a client address for limits: IPv4 address, IPv6 /64.
     *
     * @param remote the remote address
     * @return the key
     */
    static String clientKey(java.net.SocketAddress remote) {
        if (!(remote instanceof InetSocketAddress inet) || inet.getAddress() == null) {
            return "unknown";
        }
        byte[] bytes = inet.getAddress().getAddress();
        if (bytes.length == 16) {
            StringBuilder key = new StringBuilder("v6:");
            for (int i = 0; i < 8; i++) {
                key.append(String.format("%02x", bytes[i] & 0xff));
            }
            return key.toString();
        }
        return inet.getAddress().getHostAddress();
    }

    private boolean acquireConnection(String client) {
        synchronized (connectionLock) {
            int fromClient = connections.getOrDefault(client, 0);
            if (openConnections >= MAX_CONNECTIONS || fromClient >= MAX_CONNECTIONS_PER_IP) {
                return false;
            }
            openConnections++;
            connections.put(client, fromClient + 1);
            return true;
        }
    }

    private void releaseConnection(String client) {
        synchronized (connectionLock) {
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
    }

    /** Closes the listener and every connection. Idempotent. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        shutdownGroups();
        executor.shutdownNow();
    }

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

    boolean secure() {
        return tls.isPresent();
    }

    String version() {
        return version;
    }

    WebAssets assets() {
        return assets;
    }

    /**
     * The signed-in endpoints, or null when the panel runs without accounts (tests of the files).
     */
    PanelApi api() {
        return api;
    }

    Optional<String> publicUrl() {
        return settings.publicUrl();
    }

    RateLimiter<String> addressLimiter() {
        return addressLimiter;
    }

    Set<String> allowedHosts() {
        return allowedHosts;
    }

    ExecutorService executor() {
        return executor;
    }

    /** Builder of {@link PanelServer}. */
    public static final class Builder {

        private final PanelConfigLoader.Settings settings;
        private Optional<ApiTls.Material> tls = Optional.empty();
        private String version = "dev";
        private WebAssets assets;
        private PanelApi api;
        private LongSupplier nanoTime = System::nanoTime;

        private Builder(PanelConfigLoader.Settings settings) {
            this.settings = Objects.requireNonNull(settings, "settings");
        }

        /**
         * The TLS material of the API; required for any bind address other than loopback.
         *
         * @param value the material, or empty for plain HTTP on loopback
         * @return this builder
         */
        public Builder tls(Optional<ApiTls.Material> value) {
            this.tls = Objects.requireNonNull(value, "tls");
            return this;
        }

        /**
         * The version shown by {@code GET /api/panel/info}.
         *
         * @param value the version of the cloud
         * @return this builder
         */
        public Builder version(String value) {
            this.version = Objects.requireNonNull(value, "version");
            return this;
        }

        /**
         * The frontend files (tests); by default they are read from the jar.
         *
         * @param value the files
         * @return this builder
         */
        public Builder assets(WebAssets value) {
            this.assets = Objects.requireNonNull(value, "assets");
            return this;
        }

        /**
         * The accounts, sign-in and API endpoints. Without them only {@code /api/panel/info} and the
         * frontend files are served.
         *
         * @param value the endpoints
         * @return this builder
         */
        public Builder api(PanelApi value) {
            this.api = Objects.requireNonNull(value, "api");
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
        public PanelServer build() {
            return new PanelServer(this);
        }
    }
}
