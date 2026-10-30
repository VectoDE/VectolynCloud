package net.vectolyn.cloud.cluster;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import net.vectolyn.cloud.cluster.ClusterMessages.HelloOutcome;
import net.vectolyn.cloud.cluster.ClusterMessages.JoinReply;
import net.vectolyn.cloud.cluster.ClusterMessages.JoinRequest;
import net.vectolyn.cloud.cluster.ClusterMessages.NodeHello;
import net.vectolyn.cloud.cluster.ClusterMessages.NodeWelcome;
import net.vectolyn.cloud.network.ConnectionLimiter;
import net.vectolyn.protocol.NodeId;
import net.vectolyn.protocol.PacketReader;
import net.vectolyn.protocol.Protocol;
import net.vectolyn.protocol.ProtocolException;
import net.vectolyn.protocol.message.Disconnect;
import net.vectolyn.protocol.message.Envelope;
import net.vectolyn.protocol.message.Ping;
import net.vectolyn.protocol.message.Pong;
import net.vectolyn.protocol.message.Request;
import net.vectolyn.protocol.message.Response;

/**
 * The cluster port of the head: TLS 1.3, one virtual thread per connection.
 *
 * <p>A connection either
 * <ul>
 *   <li>has no client certificate: it may send exactly one {@code Request} on
 *       {@link ClusterMessages#JOIN}, gets the answer and is closed; or</li>
 *   <li>has a certificate the trust manager accepted (cluster CA, node certificate, member,
 *       not revoked): its first frame must be a {@code Request} on {@link ClusterMessages#HELLO}
 *       naming the node of the certificate. After an accepted hello it is the session of that
 *       member; a newer session of the same member replaces it.</li>
 * </ul>
 * Anything else ends the connection with {@code Disconnect(PROTOCOL_ERROR)}. Until the hello is
 * accepted, a frame may have 4 KiB and the whole handshake (TLS and request) must be done within
 * {@link Settings#handshakeTimeout()}, no matter how slowly the peer sends. Connections count
 * against the same kind of limits as the service channel ({@link ConnectionLimiter}): an address is
 * an IPv4 address or an IPv6 /64.
 */
public final class ClusterServer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ClusterServer.class.getName());
    /** How long a send waits for a write that is stuck on a peer that does not read. */
    static final Duration WRITE_WAIT = Duration.ofSeconds(1);
    /** Longest time from a join request to its answer (the head runs keytool in between). */
    static final Duration JOIN_TIMEOUT = Duration.ofSeconds(60);
    /** Pings a member may send per ping interval; more ends the session. */
    static final int PINGS_PER_INTERVAL = 32;
    /** Pause after a failed accept, so a lack of file descriptors does not spin the accept thread. */
    private static final Duration ACCEPT_BACKOFF = Duration.ofMillis(100);
    /**
     * SO_LINGER of every connection, in seconds. With it set, closing a TLS socket waits at most
     * this long for a writer that is stuck on a peer that does not read, then cuts the socket,
     * which also frees the writer; without it the JDK waits for that writer without limit.
     */
    private static final int LINGER_SECONDS = 1;

    /** What the server asks the cluster about. */
    public interface Handler {

        /**
         * A node without a certificate asks to join.
         *
         * @param request   the request
         * @param from      the peer's address
         * @param connected false once this side has closed the connection (the join deadline cut
         *                  it); nothing may be committed then. A peer that hung up on its own is
         *                  not noticed here.
         * @return the answer
         */
        JoinReply join(JoinRequest request, InetAddress from, BooleanSupplier connected);

        /**
         * A node with an accepted certificate says hello. The server has checked already that the
         * hello names the node of the certificate.
         *
         * @param hello       the hello
         * @param fingerprint SHA-256 of its certificate
         * @param from        the peer's address
         * @return the answer; only {@link HelloOutcome#ACCEPTED} keeps the connection
         */
        NodeWelcome hello(NodeHello hello, String fingerprint, InetAddress from);

        /**
         * Asked once more right after a session is registered: whether the member still belongs
         * with this certificate. Closes the gap between an accepted hello and a removal that ran
         * before the session was registered.
         *
         * @param node        the member
         * @param fingerprint SHA-256 of its certificate
         * @return whether the session may stay
         */
        boolean admitted(NodeId node, String fingerprint);

        /** A member's session started. */
        default void connected(NodeId node) {
        }

        /** A member's session ended (not called for a session that was replaced). */
        default void disconnected(NodeId node) {
        }
    }

    /**
     * Settings of the cluster port.
     *
     * @param bind             address to listen on
     * @param port             port, 0 for any free one (tests)
     * @param head             node id of this head, for refusals
     * @param version          VectolynCloud version of this head, for refusals
     * @param handshakeTimeout TLS handshake plus first request
     * @param idleTimeout      a session without any frame for this long is closed
     * @param pingInterval     the server pings every session this often
     */
    public record Settings(String bind, int port, NodeId head, String version, Duration handshakeTimeout,
                           Duration idleTimeout, Duration pingInterval) {

        /** Checks the values. */
        public Settings {
            Objects.requireNonNull(bind, "bind");
            Objects.requireNonNull(head, "head");
            ClusterMessages.checkVersion(version);
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("port out of range");
            }
        }

        /** Default timings: 10 s handshake, 30 s idle, ping every 10 s. */
        public static Settings of(String bind, int port, NodeId head, String version) {
            return new Settings(bind, port, head, version, Duration.ofSeconds(10), Duration.ofSeconds(30),
                    Duration.ofSeconds(10));
        }
    }

    private final Settings settings;
    private final SSLContext context;
    private final Handler handler;
    private final ClusterHandlers handlers;
    // 128 connections, 16 per address; before authentication 64, 8 per address. A connection
    // leaves the unauthenticated pool once its hello is accepted and the member admitted, so a
    // removed node with its old certificate counts like any anonymous peer.
    private final ConnectionLimiter limiter = new ConnectionLimiter(128, 16, 64, 8);
    private final Map<NodeId, Session> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong refused = new AtomicLong();
    private final AtomicLong acceptFailures = new AtomicLong();
    private volatile SSLServerSocket server;

    /**
     * Creates the server; nothing listens until {@link #start()}.
     *
     * @param settings where and how
     * @param context  TLS context with this head's certificate and {@link ClusterTrust#peers}
     * @param handler  join and hello decisions
     * @param handlers what the head answers on a member's session
     */
    public ClusterServer(Settings settings, SSLContext context, Handler handler, ClusterHandlers handlers) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.context = Objects.requireNonNull(context, "context");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "vectolyn-cluster-timer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Binds the port and starts accepting.
     *
     * @return the bound address
     * @throws IOException if the port cannot be bound
     */
    public InetSocketAddress start() throws IOException {
        SSLServerSocket socket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
        try {
            ClusterTls.configure(socket);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(settings.bind(), settings.port()), 64);
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
        server = socket;
        Thread accept = new Thread(this::acceptLoop, "vectolyn-cluster-accept");
        accept.setDaemon(true);
        accept.start();
        long ping = settings.pingInterval().toMillis();
        timer.scheduleAtFixedRate(this::pingAll, ping, ping, TimeUnit.MILLISECONDS);
        return (InetSocketAddress) socket.getLocalSocketAddress();
    }

    /** The members connected now. */
    public Set<NodeId> connected() {
        return Set.copyOf(sessions.keySet());
    }

    /**
     * The session of a member, for requests and pushes from the head.
     *
     * @param node the member
     * @return its session, if it has one now
     */
    public Optional<ClusterSession> session(NodeId node) {
        Session session = sessions.get(node);
        return session == null || session.ended.get() ? Optional.empty() : Optional.of(session.rpc);
    }

    /** The sessions of every member connected now. */
    public List<ClusterSession> sessions() {
        List<ClusterSession> all = new ArrayList<>();
        for (Session session : sessions.values()) {
            if (!session.ended.get()) {
                all.add(session.rpc);
            }
        }
        return all;
    }

    /**
     * Ends the session of a member, for example after its certificate was revoked.
     *
     * @param node   the member
     * @param reason sent before the connection closes
     * @return whether it had a session
     */
    public boolean disconnect(NodeId node, Disconnect.Reason reason) {
        // The thread that serves the session removes it and reports the end.
        Session session = sessions.get(node);
        if (session == null) {
            return false;
        }
        session.end(reason);
        return true;
    }

    private void acceptLoop() {
        SSLServerSocket socket = server;
        while (!closed.get()) {
            Socket accepted;
            try {
                accepted = socket.accept();
            } catch (IOException e) {
                if (!closed.get()) {
                    // For example out of file descriptors: wait a moment instead of spinning, and
                    // log the first failure and every 1000th only.
                    long count = acceptFailures.incrementAndGet();
                    if (count == 1 || count % 1000 == 0) {
                        LOG.log(System.Logger.Level.WARNING, "Cluster port: accept failed ({0} so far): {1}",
                                count, e.getMessage());
                    }
                    try {
                        Thread.sleep(ACCEPT_BACKOFF.toMillis());
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                continue;
            }
            ConnectionLimiter.Slot slot = limiter.tryAcquire(accepted.getInetAddress());
            if (slot == null) {
                long count = refused.incrementAndGet();
                if (count == 1 || count % 1000 == 0) {
                    LOG.log(System.Logger.Level.WARNING, "Cluster port: connection limit reached, refused {0} so far",
                            count);
                }
                closeQuietly(accepted);
                continue;
            }
            Thread.ofVirtual().name("vectolyn-cluster-conn").start(() -> serve((SSLSocket) accepted, slot));
        }
    }

    private void serve(SSLSocket socket, ConnectionLimiter.Slot slot) {
        // The deadline closes on a thread of its own: a close that waits must not hold up the
        // single timer thread and with it every other deadline and ping.
        ScheduledFuture<?> deadline = timer.schedule(() -> forceClose(socket),
                settings.handshakeTimeout().toMillis(), TimeUnit.MILLISECONDS);
        NodeId member = null;
        Session session = null;
        try {
            socket.setSoLinger(true, LINGER_SECONDS);
            socket.setSoTimeout((int) settings.handshakeTimeout().toMillis());
            socket.setTcpNoDelay(true);
            socket.startHandshake();
            SSLSession tls = socket.getSession();
            if (!ClusterTls.PROTOCOL.equals(tls.getProtocol())) {
                return;
            }
            // A certificate of the cluster CA alone does not take the connection out of the
            // unauthenticated pool: a removed node keeps its certificate. Only an accepted hello does.
            Optional<X509Certificate> peer = peerCertificate(tls);
            FrameStream stream = new FrameStream(socket.getInputStream(), socket.getOutputStream());
            Envelope first = stream.read(Protocol.MAX_HANDSHAKE_FRAME_SIZE);
            if (!(first.message() instanceof Request request)) {
                stream.write(Envelope.of(new Disconnect(Disconnect.Reason.PROTOCOL_ERROR)));
                return;
            }
            if (peer.isEmpty()) {
                if (!request.channel().equals(ClusterMessages.JOIN)) {
                    stream.write(Envelope.of(new Disconnect(Disconnect.Reason.AUTH_FAILED)));
                    return;
                }
                JoinRequest join = JoinRequest.read(new PacketReader(request.payload()));
                // The request is complete; from here the head's own work (keytool) takes the time,
                // and a cut connection would cost the token. A longer limit still bounds the answer.
                deadline.cancel(false);
                deadline = timer.schedule(() -> forceClose(socket), JOIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                JoinReply reply = handler.join(join, socket.getInetAddress(), () -> !socket.isClosed());
                stream.write(new Envelope(first.requestId(), new Response(Response.Status.OK, reply.write())));
                return;
            }
            if (!request.channel().equals(ClusterMessages.HELLO)) {
                stream.write(Envelope.of(new Disconnect(Disconnect.Reason.PROTOCOL_ERROR)));
                return;
            }
            NodeHello hello = NodeHello.read(new PacketReader(request.payload()));
            X509Certificate certificate = peer.get();
            Optional<NodeId> certified = NodeCertificates.nodeId(certificate);
            NodeWelcome welcome;
            if (certified.isEmpty() || !certified.get().equals(hello.node())) {
                welcome = new NodeWelcome(HelloOutcome.NODE_MISMATCH, settings.head(), settings.version());
            } else {
                welcome = handler.hello(hello, NodeCertificates.fingerprint(certificate), socket.getInetAddress());
            }
            stream.write(new Envelope(first.requestId(), new Response(Response.Status.OK, welcome.write())));
            if (welcome.outcome() != HelloOutcome.ACCEPTED) {
                return;
            }
            deadline.cancel(false);
            if (closed.get()) {
                return;
            }
            member = hello.node();
            session = new Session(member, socket, stream, settings.pingInterval(), handlers);
            Session old = sessions.put(member, session);
            if (old != null) {
                old.end(Disconnect.Reason.REPLACED);
            }
            if (closed.get() || !handler.admitted(member, NodeCertificates.fingerprint(certificate))) {
                // removed between the hello and now, or close() ran past this session
                boolean mine = sessions.remove(member, session);
                session.ended.set(true);
                if (mine && old != null) {
                    // The replaced session does not report its end (it is no longer in the map),
                    // so the member's connected event gets its disconnected one here.
                    handler.disconnected(member);
                }
                stream.tryWrite(Envelope.of(new Disconnect(closed.get() ? Disconnect.Reason.SHUTDOWN
                        : Disconnect.Reason.AUTH_FAILED)), WRITE_WAIT);
                return;
            }
            slot.authenticated();
            handler.connected(member);
            socket.setSoTimeout((int) settings.idleTimeout().toMillis());
            session.run();
        } catch (SocketTimeoutException e) {
            if (session != null) {
                session.endHere(Disconnect.Reason.TIMEOUT);
            }
        } catch (ProtocolException e) {
            LOG.log(System.Logger.Level.DEBUG, "Cluster port: protocol error from {0}: {1}",
                    socket.getInetAddress().getHostAddress(), e.getMessage());
            if (session != null) {
                session.endHere(Disconnect.Reason.PROTOCOL_ERROR);
            } else {
                sendQuietly(socket, Disconnect.Reason.PROTOCOL_ERROR);
            }
        } catch (EOFException | SocketException e) {
            // closed by the peer, the deadline or close()
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Cluster port: connection from {0} ended: {1}",
                    socket.getInetAddress().getHostAddress(), e.getMessage());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Cluster port: error on a connection", e);
        } finally {
            deadline.cancel(false);
            if (session != null) {
                session.rpc.close();
            }
            if (member != null && session != null && sessions.remove(member, session)) {
                handler.disconnected(member);
            }
            slot.release();
            closeQuietly(socket);
        }
    }

    private static Optional<X509Certificate> peerCertificate(SSLSession tls) {
        try {
            Certificate[] chain = tls.getPeerCertificates();
            return chain.length > 0 && chain[0] instanceof X509Certificate leaf ? Optional.of(leaf) : Optional.empty();
        } catch (SSLPeerUnverifiedException e) {
            return Optional.empty();
        }
    }

    private void pingAll() {
        // Off the timer thread: a write that is stuck on a peer that does not read must not hold up
        // the handshake deadlines. The next ping finds the write lock taken and ends that session.
        for (Session session : sessions.values()) {
            long nonce = ThreadLocalRandom.current().nextLong();
            Thread.ofVirtual().name("vectolyn-cluster-ping").start(() -> session.send(Envelope.of(new Ping(nonce))));
        }
    }

    private static void sendQuietly(SSLSocket socket, Disconnect.Reason reason) {
        try {
            new FrameStream(socket.getInputStream(), socket.getOutputStream()).write(Envelope.of(new Disconnect(reason)));
        } catch (IOException | RuntimeException e) {
            // the connection is gone already
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // nothing left to do
        }
    }

    /**
     * Cuts a connection without waiting for anything, on a thread of its own: no close_notify, a
     * reset instead of a graceful end, and a thread stuck writing to it is freed.
     */
    private static void forceClose(Socket socket) {
        Thread.ofVirtual().name("vectolyn-cluster-close").start(() -> {
            try {
                socket.setSoLinger(true, 0);
            } catch (IOException e) {
                // closed already
            }
            closeQuietly(socket);
        });
    }

    /** Stops accepting, ends every session with {@code Disconnect(SHUTDOWN)} and closes the port. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        SSLServerSocket socket = server;
        if (socket != null) {
            closeQuietly(socket);
        }
        for (NodeId node : Set.copyOf(sessions.keySet())) {
            disconnect(node, Disconnect.Reason.SHUTDOWN);
        }
        timer.shutdownNow();
    }

    private static void closeQuietly(SSLServerSocket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // nothing left to do
        }
    }

    /** The session of one member. */
    private static final class Session {

        private final NodeId node;
        private final SSLSocket socket;
        private final FrameStream stream;
        private final long window;
        private final AtomicBoolean ended = new AtomicBoolean();
        private final ClusterSession rpc;
        private long windowStart = System.nanoTime();
        private int pings;

        Session(NodeId node, SSLSocket socket, FrameStream stream, Duration pingInterval, ClusterHandlers handlers) {
            this.node = node;
            this.socket = socket;
            this.stream = stream;
            this.window = pingInterval.toNanos();
            this.rpc = new ClusterSession(node, handlers, this::trySend);
        }

        void run() throws IOException {
            while (!ended.get()) {
                Envelope envelope = stream.read(Protocol.MAX_FRAME_SIZE);
                if (envelope.message() instanceof Pong) {
                    // the session is alive; reading it reset the idle timeout
                    continue;
                }
                switch (envelope.message()) {
                    case Ping ping -> {
                        if (tooManyPings()) {
                            LOG.log(System.Logger.Level.WARNING, "Cluster: {0} sends more than {1} pings per interval; "
                                    + "session ended", node, PINGS_PER_INTERVAL);
                            endHere(Disconnect.Reason.PROTOCOL_ERROR);
                            return;
                        }
                        send(Envelope.of(new Pong(ping.nonce())));
                    }
                    case Disconnect disconnect -> {
                        LOG.log(System.Logger.Level.DEBUG, "Cluster: {0} disconnected ({1})", node, disconnect.reason());
                        return;
                    }
                    default -> {
                        // requests, answers and pushes; anything else breaks the protocol
                        if (!rpc.receive(envelope)) {
                            endHere(Disconnect.Reason.PROTOCOL_ERROR);
                            return;
                        }
                    }
                }
            }
        }

        private boolean tooManyPings() {
            long now = System.nanoTime();
            if (now - windowStart > window) {
                windowStart = now;
                pings = 0;
            }
            return ++pings > PINGS_PER_INTERVAL;
        }

        void send(Envelope envelope) {
            trySend(envelope);
        }

        /** Sends a frame; if that fails, the session ends. */
        boolean trySend(Envelope envelope) {
            if (ended.get()) {
                return false;
            }
            try {
                if (stream.tryWrite(envelope, WRITE_WAIT)) {
                    return true;
                }
            } catch (IOException e) {
                // ended below
            }
            end(null);
            return false;
        }

        /**
         * Ends the session from the thread that serves it: sends the reason here, waiting at most
         * {@link #WRITE_WAIT}, before that thread closes the socket in its {@code finally}. The
         * asynchronous {@link #end} would race that close and lose the reason.
         */
        void endHere(Disconnect.Reason reason) {
            if (!ended.compareAndSet(false, true)) {
                return;
            }
            try {
                stream.tryWrite(Envelope.of(new Disconnect(reason)), WRITE_WAIT);
            } catch (IOException e) {
                // the peer is gone
            }
        }

        /**
         * Ends the session without making the caller wait: the reason is sent and the socket closed
         * on a thread of their own, and if that send is stuck the socket is cut after a moment.
         */
        void end(Disconnect.Reason reason) {
            if (!ended.compareAndSet(false, true)) {
                return;
            }
            Thread sender = Thread.ofVirtual().name("vectolyn-cluster-end").start(() -> {
                if (reason != null) {
                    try {
                        stream.tryWrite(Envelope.of(new Disconnect(reason)), WRITE_WAIT);
                    } catch (IOException e) {
                        // the peer is gone
                    }
                }
                closeQuietly(socket);
            });
            Thread.ofVirtual().name("vectolyn-cluster-end").start(() -> {
                try {
                    if (!sender.join(WRITE_WAIT.multipliedBy(3))) {
                        forceClose(socket);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    forceClose(socket);
                }
            });
        }
    }
}
