package net.vectolyn.cloud.cluster;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import net.vectolyn.cloud.cluster.ClusterMessages.HelloOutcome;
import net.vectolyn.cloud.cluster.ClusterMessages.NodeHello;
import net.vectolyn.cloud.cluster.ClusterMessages.NodeWelcome;
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
 * A member's connection to the head: mutual TLS 1.3 with its node certificate, a hello with its
 * node id and version, then pings both ways. It reconnects on its own: after a lost connection
 * with a wait from 1 s doubling to 30 s (with jitter), after a refusal (another version, revoked,
 * unknown) every 5 minutes, since only the operator can fix that.
 */
public final class ClusterLink implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ClusterLink.class.getName());
    /** Wait after a refusal. */
    static final Duration REFUSED_RETRY = Duration.ofMinutes(5);

    /** Connection state. */
    public enum State {
        /** Not connected, trying. */
        CONNECTING,
        /** Session with the head. */
        CONNECTED,
        /** The head refused this node; see {@link #lastRefusal()}. */
        REFUSED,
        /** Closed. */
        CLOSED
    }

    /** What the node learns from the link. */
    public interface Listener {

        /**
         * The session with the head started. Runs on the link's reader thread: it must not wait
         * for an answer on the session.
         *
         * @param head    the head
         * @param session requests and pushes to the head
         */
        void connected(NodeId head, ClusterSession session);

        /**
         * The head refused this node.
         *
         * @param outcome     why
         * @param headVersion the head's version; for {@link HelloOutcome#VERSION_MISMATCH} the
         *                    version this node has to be updated to
         */
        default void refused(HelloOutcome outcome, String headVersion) {
        }

        /** The session ended. */
        default void disconnected() {
        }
    }

    /**
     * Settings of the link.
     *
     * @param self         this node
     * @param version      its VectolynCloud version
     * @param head         address of the head's cluster port
     * @param timeout      connect, TLS and hello
     * @param idleTimeout  a session without any frame for this long is closed
     * @param pingInterval the link pings the head this often
     * @param firstRetry   first wait after a lost connection
     * @param maxRetry     longest wait after a lost connection
     * @param refusedRetry wait after a refusal
     */
    public record Settings(NodeId self, String version, InetSocketAddress head, Duration timeout,
                           Duration idleTimeout, Duration pingInterval, Duration firstRetry, Duration maxRetry,
                           Duration refusedRetry) {

        /** Checks the values. */
        public Settings {
            Objects.requireNonNull(self, "self");
            ClusterMessages.checkVersion(version);
            Objects.requireNonNull(head, "head");
        }

        /** The default timings. */
        public static Settings of(NodeId self, String version, InetSocketAddress head) {
            return new Settings(self, version, head, Duration.ofSeconds(10), Duration.ofSeconds(30),
                    Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(30), REFUSED_RETRY);
        }
    }

    private final Settings settings;
    private final SSLContext context;
    private final Listener listener;
    private final ClusterHandlers handlers;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ClusterSession rpc;
    private final ScheduledExecutorService timer;
    private final Object wait = new Object();
    private volatile State state = State.CONNECTING;
    private volatile HelloOutcome lastRefusal;
    private volatile String lastHeadVersion;
    private volatile SSLSocket current;
    private volatile FrameStream currentStream;
    private Thread thread;

    /**
     * Creates the link; it connects after {@link #start()}.
     *
     * @param settings where and how
     * @param context  TLS context with this node's certificate and {@link ClusterTrust#peers}
     *                 (which checks that the peer is the head)
     * @param listener what the node learns
     * @param handlers what this member answers on the session
     */
    public ClusterLink(Settings settings, SSLContext context, Listener listener, ClusterHandlers handlers) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.context = Objects.requireNonNull(context, "context");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vectolyn-cluster-link-timer");
            t.setDaemon(true);
            return t;
        });
    }

    /** Starts connecting in the background. */
    public synchronized void start() {
        if (thread != null) {
            throw new IllegalStateException("already started");
        }
        thread = new Thread(this::loop, "vectolyn-cluster-link");
        thread.setDaemon(true);
        thread.start();
    }

    /** The state now. */
    public State state() {
        return state;
    }

    /** The session with the head, while there is one. */
    public Optional<ClusterSession> session() {
        ClusterSession current = rpc;
        return current == null || current.isClosed() ? Optional.empty() : Optional.of(current);
    }

    /** Why the head refused this node last, if it did. */
    public HelloOutcome lastRefusal() {
        return lastRefusal;
    }

    /** The head's version from its last answer, if any. */
    public String lastHeadVersion() {
        return lastHeadVersion;
    }

    private void loop() {
        Duration retry = settings.firstRetry();
        while (!closed.get()) {
            Duration next;
            try {
                HelloOutcome outcome = connectOnce();
                if (outcome == HelloOutcome.ACCEPTED) {
                    retry = settings.firstRetry();
                    next = retry;
                } else {
                    next = settings.refusedRetry();
                }
            } catch (IOException | ProtocolException e) {
                if (!closed.get()) {
                    state = State.CONNECTING;
                    LOG.log(System.Logger.Level.DEBUG, "Cluster: no session with the head ({0})", e.getMessage());
                }
                next = jitter(retry);
                retry = retry.multipliedBy(2).compareTo(settings.maxRetry()) > 0 ? settings.maxRetry()
                        : retry.multipliedBy(2);
            }
            sleep(next);
        }
        state = State.CLOSED;
    }

    /** One connection: hello and, if accepted, the session until it ends. */
    private HelloOutcome connectOnce() throws IOException {
        int timeout = (int) settings.timeout().toMillis();
        try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket()) {
            current = socket;
            if (closed.get()) {
                return HelloOutcome.ACCEPTED;
            }
            // TLS 1.3 only, endpoint identification on: the trust manager (ClusterTrust.peers)
            // identifies the head by the node id in its certificate.
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setProtocols(new String[] {ClusterTls.PROTOCOL});
            parameters.setEndpointIdentificationAlgorithm(ClusterTls.ENDPOINT_IDENTIFICATION);
            socket.setSSLParameters(parameters);
            socket.connect(settings.head(), timeout);
            socket.setSoTimeout(timeout);
            socket.setTcpNoDelay(true);
            // A close waits at most this long for a write that is stuck, then cuts the socket
            // (see ClusterServer): the pings run on the timer thread and close() under the
            // service's lock.
            socket.setSoLinger(true, 1);
            socket.startHandshake();
            if (!ClusterTls.PROTOCOL.equals(socket.getSession().getProtocol())) {
                throw new IOException("the head did not speak TLS 1.3");
            }
            FrameStream stream = new FrameStream(socket.getInputStream(), socket.getOutputStream());
            currentStream = stream;
            stream.write(new Envelope(1, new Request(ClusterMessages.HELLO,
                    new NodeHello(settings.self(), settings.version()).write())));
            Envelope answer = stream.read(Protocol.MAX_HANDSHAKE_FRAME_SIZE);
            if (!(answer.message() instanceof Response response) || answer.requestId() != 1
                    || response.status() != Response.Status.OK) {
                throw new IOException("the head did not answer the hello");
            }
            NodeWelcome welcome = NodeWelcome.read(new PacketReader(response.payload()));
            lastHeadVersion = welcome.headVersion();
            if (welcome.outcome() != HelloOutcome.ACCEPTED) {
                state = State.REFUSED;
                lastRefusal = welcome.outcome();
                listener.refused(welcome.outcome(), welcome.headVersion());
                return welcome.outcome();
            }
            lastRefusal = null;
            ClusterSession session = new ClusterSession(welcome.head(), handlers, e -> send(stream, socket, e));
            rpc = session;
            state = State.CONNECTED;
            socket.setSoTimeout((int) settings.idleTimeout().toMillis());
            long ping = settings.pingInterval().toMillis();
            ScheduledFuture<?> pings = timer.scheduleAtFixedRate(() -> send(stream, socket,
                    Envelope.of(new Ping(ThreadLocalRandom.current().nextLong()))), ping, ping, TimeUnit.MILLISECONDS);
            try {
                // The listener must not wait for an answer here: this thread reads the answers.
                listener.connected(welcome.head(), session);
                while (!closed.get()) {
                    Envelope envelope = stream.read(Protocol.MAX_FRAME_SIZE);
                    if (envelope.message() instanceof Pong) {
                        // alive; reading it reset the idle timeout
                        continue;
                    }
                    switch (envelope.message()) {
                        case Ping p -> send(stream, socket, Envelope.of(new Pong(p.nonce())));
                        case Disconnect d -> {
                            LOG.log(System.Logger.Level.INFO, "Cluster: the head ended the session ({0})", d.reason());
                            return HelloOutcome.ACCEPTED;
                        }
                        default -> {
                            // requests, answers and pushes; anything else breaks the protocol
                            if (!session.receive(envelope)) {
                                send(stream, socket, Envelope.of(new Disconnect(Disconnect.Reason.PROTOCOL_ERROR)));
                                return HelloOutcome.ACCEPTED;
                            }
                        }
                    }
                }
                send(stream, socket, Envelope.of(new Disconnect(Disconnect.Reason.SHUTDOWN)));
                return HelloOutcome.ACCEPTED;
            } catch (SocketTimeoutException e) {
                send(stream, socket, Envelope.of(new Disconnect(Disconnect.Reason.TIMEOUT)));
                return HelloOutcome.ACCEPTED;
            } catch (EOFException | SocketException e) {
                return HelloOutcome.ACCEPTED;
            } finally {
                pings.cancel(false);
                rpc = null;
                session.close();
                if (state == State.CONNECTED) {
                    state = State.CONNECTING;
                }
                listener.disconnected();
            }
        } finally {
            current = null;
            currentStream = null;
        }
    }

    /** Sends a frame; if that fails, the socket is closed, which ends the session. */
    private static boolean send(FrameStream stream, SSLSocket socket, Envelope envelope) {
        try {
            if (stream.tryWrite(envelope, ClusterServer.WRITE_WAIT)) {
                return true;
            }
        } catch (IOException e) {
            // closed below
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // gone
        }
        return false;
    }

    private static Duration jitter(Duration step) {
        long millis = step.toMillis();
        return Duration.ofMillis(millis / 2 + ThreadLocalRandom.current().nextLong(millis / 2 + 1));
    }

    private void sleep(Duration duration) {
        synchronized (wait) {
            if (closed.get()) {
                return;
            }
            try {
                wait.wait(Math.max(1, duration.toMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                closed.set(true);
            }
        }
    }

    /** Ends the session ({@code Disconnect(SHUTDOWN)}) and stops reconnecting. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (wait) {
            wait.notifyAll();
        }
        SSLSocket socket = current;
        FrameStream stream = currentStream;
        if (socket != null) {
            if (stream != null) {
                try {
                    stream.tryWrite(Envelope.of(new Disconnect(Disconnect.Reason.SHUTDOWN)), ClusterServer.WRITE_WAIT);
                } catch (IOException | RuntimeException e) {
                    // gone
                }
            }
            try {
                socket.close();
            } catch (IOException e) {
                // gone
            }
        }
        timer.shutdownNow();
        Thread running = thread;
        if (running != null) {
            try {
                running.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        state = State.CLOSED;
    }
}
