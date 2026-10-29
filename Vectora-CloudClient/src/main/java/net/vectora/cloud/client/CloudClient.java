package net.vectora.cloud.client;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.SocketFactory;
import net.vectora.protocol.ChannelName;
import net.vectora.protocol.IncompatibleVersionException;
import net.vectora.protocol.Protocol;
import net.vectora.protocol.ProtocolException;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.AuthResult;
import net.vectora.protocol.message.ChannelMessage;
import net.vectora.protocol.message.Disconnect;
import net.vectora.protocol.message.Envelope;
import net.vectora.protocol.message.Hello;
import net.vectora.protocol.message.Message;
import net.vectora.protocol.message.MessageCodec;
import net.vectora.protocol.message.MessageLimits;
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
 * Connects a proxy or server to VectoraCloud. Uses only the JDK (a blocking socket and three
 * daemon threads) and {@code Vectora-Protocol}, so it brings no library onto the server's class
 * path and runs on every platform from Java 21 on.
 *
 * <p>Typical use in a platform bridge:
 * <pre>{@code
 * CloudClient client = CloudClient.create(CloudClientSettings.fromEnvironment());
 * client.addListener(listener);
 * client.installShutdownHook();
 * client.start();
 * // once the server accepts players:
 * client.markReady();
 * // every few seconds:
 * client.reportStatus(online, max, tps, mspt);
 * }</pre>
 *
 * <p>The client keeps the connection up on its own: it reconnects with a growing wait (see
 * {@link CloudClientSettings}), sends {@code Ping} while connected, treats a silent connection as
 * dead and repeats {@code ServiceReady} and the last status after a reconnect. Messages sent while
 * it is not connected are dropped (the methods return {@code false}); requests fail with
 * {@link NotConnectedException}.
 *
 * <p>Thread-safe. Listener, subscriber and handler calls run on one event thread of the client,
 * never on a server thread.
 */
public final class CloudClient implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(CloudClient.class.getName());
    private static final byte[] EMPTY = new byte[0];
    /** How long {@link #close()} waits for the goodbye to be written before it closes the socket. */
    private static final long GOODBYE_GRACE_MILLIS = 500;

    /** Requests this client may have open at the same time. */
    static final int MAX_PENDING_REQUESTS = 256;

    private final CloudClientSettings settings;
    private final SocketFactory socketFactory;
    private final MessageCodec codec = MessageCodec.standard();
    private final List<CloudClientListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<ChannelName, List<ChannelSubscriber>> subscribers = new ConcurrentHashMap<>();
    private final Map<ChannelName, RequestHandler> handlers = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<Response>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger nextRequestId = new AtomicInteger();
    private final ScheduledExecutorService timer;
    private final ExecutorService events;
    private final Object lifecycle = new Object();

    /** The connection being opened or open, authenticated or not; closed by {@link #close()}. */
    private volatile Connection current;
    /** The authenticated connection, or {@code null}. */
    private volatile Connection connection;
    private volatile boolean ready;
    private volatile ServiceStatus lastStatus;
    private volatile boolean closed;
    private Thread connector;
    private Thread shutdownHook;

    private CloudClient(CloudClientSettings settings, SocketFactory socketFactory) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.socketFactory = Objects.requireNonNull(socketFactory, "socketFactory");
        this.timer = Executors.newSingleThreadScheduledExecutor(daemon("vectora-cloud-client-timer"));
        this.events = Executors.newSingleThreadExecutor(daemon("vectora-cloud-client-events"));
    }

    /** Creates a client with plain TCP. It does nothing before {@link #start()}. */
    public static CloudClient create(CloudClientSettings settings) {
        return new CloudClient(settings, SocketFactory.getDefault());
    }

    /**
     * Creates a client that opens its socket with {@code socketFactory}, for example an
     * {@code SSLSocketFactory} once the channel supports TLS.
     */
    public static CloudClient create(CloudClientSettings settings, SocketFactory socketFactory) {
        return new CloudClient(settings, socketFactory);
    }

    /** Returns the id of this service. */
    public ServiceId serviceId() {
        return settings.service();
    }

    /** Returns the settings. {@code toString()} of the settings does not show the token. */
    public CloudClientSettings settings() {
        return settings;
    }

    /**
     * Starts connecting in the background. Calling it again does nothing.
     *
     * @throws IllegalStateException if the client was closed
     */
    public void start() {
        synchronized (lifecycle) {
            if (closed) {
                throw new IllegalStateException("the client is closed");
            }
            if (connector != null) {
                return;
            }
            connector = daemon("vectora-cloud-client").newThread(this::run);
            connector.start();
            long ping = settings.pingInterval().toMillis();
            timer.scheduleAtFixedRate(this::ping, ping, ping, TimeUnit.MILLISECONDS);
        }
    }

    /** Returns whether the cloud has accepted this client and the connection is open. */
    public boolean isConnected() {
        return connection != null;
    }

    /** Adds a listener for connection events and messages from the cloud. */
    public void addListener(CloudClientListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Removes a listener. */
    public void removeListener(CloudClientListener listener) {
        listeners.remove(listener);
    }

    /**
     * Tells the cloud that the server has started and accepts players. Repeated automatically
     * after a reconnect.
     *
     * @return whether it was sent now
     */
    public boolean markReady() {
        ready = true;
        return sendNow(Envelope.of(new ServiceReady(serviceId())));
    }

    /**
     * Sends a status report with the heap figures of this JVM. Values outside the protocol's
     * ranges are clamped (NaN counts as 0). The last report is repeated after a reconnect.
     *
     * @return whether it was sent now
     */
    public boolean reportStatus(int playersOnline, int maxPlayers, double tps, double mspt) {
        Runtime runtime = Runtime.getRuntime();
        long heapMax = runtime.maxMemory() == Long.MAX_VALUE ? ServiceStatus.UNKNOWN_HEAP_MAX : runtime.maxMemory();
        long heapUsed = Math.max(0, runtime.totalMemory() - runtime.freeMemory());
        return reportStatus(new ServiceStatus(serviceId(), clampCount(playersOnline), clampCount(maxPlayers),
                clamp(tps, MessageLimits.MAX_TPS), clamp(mspt, MessageLimits.MAX_MSPT), heapUsed, heapMax));
    }

    /**
     * Sends a status report. The last report is repeated after a reconnect.
     *
     * @return whether it was sent now
     * @throws IllegalArgumentException if the report is about another service
     */
    public boolean reportStatus(ServiceStatus status) {
        if (!status.service().equals(serviceId())) {
            throw new IllegalArgumentException("a service may only report its own status");
        }
        lastStatus = status;
        return sendNow(Envelope.of(status));
    }

    /**
     * Sends a player event of a proxy ({@code PlayerJoin}, {@code PlayerQuit},
     * {@code PlayerSwitch}). Not repeated after a reconnect.
     *
     * @return whether it was sent now
     * @throws IllegalArgumentException for any other message type; use the dedicated methods
     */
    public boolean send(Message message) {
        if (!(message instanceof PlayerJoin || message instanceof PlayerQuit || message instanceof PlayerSwitch)) {
            throw new IllegalArgumentException(message.getClass().getSimpleName()
                    + " is not sent with send(); use markReady, reportStatus, publish or request");
        }
        return sendNow(Envelope.of(message));
    }

    /**
     * Publishes a message on a channel. The cloud passes it to its own subscribers of the channel
     * and to every other connected service; this client does not receive it back.
     *
     * @return whether it was sent now
     * @throws IllegalArgumentException if the payload exceeds 64 KiB
     */
    public boolean publish(ChannelName channel, byte[] payload) {
        return sendNow(Envelope.of(new ChannelMessage(channel, payload)));
    }

    /**
     * Receives the messages published on {@code channel} by other services and the cloud.
     *
     * @return closing it ends the subscription
     */
    public Subscription subscribe(ChannelName channel, ChannelSubscriber subscriber) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(subscriber, "subscriber");
        subscribers.computeIfAbsent(channel, key -> new CopyOnWriteArrayList<>()).add(subscriber);
        return () -> subscribers.computeIfPresent(channel, (key, list) -> {
            list.remove(subscriber);
            return list.isEmpty() ? null : list;
        });
    }

    /**
     * Sends a request to the cloud. The future completes with the cloud's response (which may
     * have the status {@code NO_HANDLER}), or fails with {@link TimeoutException} after the
     * request timeout or with {@link NotConnectedException}. It completes on the event thread.
     *
     * @throws IllegalArgumentException if the payload exceeds 64 KiB
     */
    public CompletableFuture<Response> request(ChannelName channel, byte[] payload) {
        Request request = new Request(channel, payload);
        CompletableFuture<Response> future = new CompletableFuture<>();
        Connection open = connection;
        if (open == null) {
            future.completeExceptionally(new NotConnectedException("not connected to the cloud"));
            return future;
        }
        if (pending.size() >= MAX_PENDING_REQUESTS) {
            future.completeExceptionally(new IllegalStateException("too many open requests"));
            return future;
        }
        int id = reserveRequestId();
        pending.put(id, future);
        long timeout = settings.requestTimeout().toMillis();
        ScheduledFuture<?> expiry = timer.schedule(() -> {
            future.completeExceptionally(new TimeoutException("no response within " + timeout + " ms"));
        }, timeout, TimeUnit.MILLISECONDS);
        future.whenComplete((response, error) -> {
            pending.remove(id, future);
            expiry.cancel(false);
        });
        if (!send(open, new Envelope(id, request))) {
            future.completeExceptionally(new NotConnectedException("the connection closed while sending"));
        }
        return future;
    }

    /**
     * Registers the handler for requests the cloud sends on {@code channel}; one per channel.
     *
     * @return closing it removes the handler
     * @throws IllegalStateException if the channel already has a handler
     */
    public Subscription handleRequests(ChannelName channel, RequestHandler handler) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(handler, "handler");
        if (handlers.putIfAbsent(channel, handler) != null) {
            throw new IllegalStateException("channel " + channel + " already has a request handler");
        }
        return () -> handlers.remove(channel, handler);
    }

    /**
     * Registers a JVM shutdown hook that calls {@link #close()}, so the cloud hears
     * {@code Disconnect(SHUTDOWN)} even if the server stops without disabling its plugins.
     * Calling it again does nothing; {@link #close()} removes the hook.
     */
    public void installShutdownHook() {
        synchronized (lifecycle) {
            if (shutdownHook != null || closed) {
                return;
            }
            shutdownHook = new Thread(this::close, "vectora-cloud-client-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
    }

    /**
     * Sends {@code Disconnect(SHUTDOWN)} if connected, closes the connection, fails open requests
     * and stops the client's threads. Returns within about a second. Safe to call more than once.
     */
    @Override
    public void close() {
        Thread hook;
        Thread reader;
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
            hook = shutdownHook;
            reader = connector;
        }
        Connection open = connection;
        if (open != null) {
            // A cloud that stopped reading leaves a writer blocked in write() with the write lock
            // held; the goodbye would wait for it forever. The watchdog closes the socket after a
            // short grace period, which fails both writes and lets close() go on.
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(GOODBYE_GRACE_MILLIS);
                    open.close();
                } catch (InterruptedException sent) {
                    // The goodbye went out in time.
                }
            }, "vectora-cloud-client-close-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            send(open, Envelope.of(new Disconnect(Disconnect.Reason.SHUTDOWN)));
            watchdog.interrupt();
        }
        Connection any = current;
        if (any != null) {
            any.close();
        }
        if (reader != null) {
            reader.interrupt();
            try {
                reader.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        failPending("the client is closed");
        timer.shutdownNow();
        events.shutdown();
        try {
            events.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (hook != null && Thread.currentThread() != hook) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shuttingDown) {
                // The JVM is already shutting down; the hook runs or has run anyway.
            }
        }
    }

    // ---- connection loop on the connector thread ----

    private void run() {
        Backoff backoff = new Backoff(settings.minBackoff(), settings.maxBackoff(), new SplittableRandom());
        int failures = 0;
        while (!closed) {
            boolean accepted = false;
            Disconnect.Reason reason = null;
            Connection attempt = null;
            try {
                attempt = new Connection(socketFactory.createSocket());
                current = attempt;
                if (closed) {
                    break;
                }
                attempt.open(settings, codec);
                AuthResult result = handshake(attempt);
                if (result.outcome() == AuthResult.Outcome.ACCEPTED) {
                    accepted = true;
                    failures = 0;
                    backoff.reset();
                    connection = attempt;
                    LOG.log(Level.INFO, "Connected to VectoraCloud at {0}:{1} as {2}", settings.host(),
                            String.valueOf(settings.port()), serviceId().value());
                    onConnected(attempt);
                    reason = readLoop(attempt);
                } else {
                    LOG.log(Level.WARNING, "VectoraCloud refused service {0}: {1}", serviceId().value(),
                            result.outcome());
                    AuthResult.Outcome outcome = result.outcome();
                    notifyListeners(new CloudClientEvent.Rejected(outcome));
                }
            } catch (IncompatibleVersionException e) {
                LOG.log(Level.ERROR, "VectoraCloud speaks protocol {0}, this client {1}; update the plugin",
                        e.remoteVersion(), Protocol.PROTOCOL_VERSION);
                sendQuietly(attempt, Disconnect.Reason.INCOMPATIBLE_VERSION);
            } catch (ProtocolException e) {
                LOG.log(Level.WARNING, "Invalid data from VectoraCloud, reconnecting: {0}", e.getMessage());
                sendQuietly(attempt, Disconnect.Reason.PROTOCOL_ERROR);
            } catch (SocketTimeoutException e) {
                LOG.log(Level.WARNING, "VectoraCloud did not answer in time, reconnecting");
                sendQuietly(attempt, Disconnect.Reason.TIMEOUT);
            } catch (IOException e) {
                if (!closed) {
                    failures++;
                    // The first failure is worth a warning, a cloud that stays away is not worth one per try.
                    Level level = failures == 1 ? Level.WARNING : Level.DEBUG;
                    LOG.log(level, "No connection to VectoraCloud at {0}:{1}: {2}", settings.host(),
                            String.valueOf(settings.port()), e.getMessage());
                }
            } finally {
                connection = null;
                if (attempt != null) {
                    attempt.close();
                }
                current = null;
            }
            if (accepted) {
                failPending("the connection to the cloud closed");
                notifyListeners(new CloudClientEvent.Disconnected(reason));
            }
            if (closed) {
                break;
            }
            try {
                Thread.sleep(backoff.nextMillis());
            } catch (InterruptedException e) {
                if (closed) {
                    break;
                }
            }
        }
    }

    private AuthResult handshake(Connection attempt) throws IOException {
        attempt.socket.setSoTimeout((int) settings.connectTimeout().toMillis());
        send(attempt, Envelope.of(new Hello(Protocol.PROTOCOL_VERSION, serviceId(), settings.token())));
        Message first = attempt.reader.read().message();
        if (!(first instanceof AuthResult result)) {
            throw new ProtocolException("expected AuthResult, got " + first.getClass().getSimpleName());
        }
        if (result.outcome() == AuthResult.Outcome.ACCEPTED) {
            attempt.reader.authenticated();
            attempt.socket.setSoTimeout((int) settings.idleTimeout().toMillis());
        }
        return result;
    }

    private void onConnected(Connection open) {
        if (ready) {
            send(open, Envelope.of(new ServiceReady(serviceId())));
        }
        ServiceStatus status = lastStatus;
        if (status != null) {
            send(open, Envelope.of(status));
        }
        notifyListeners(new CloudClientEvent.Connected());
    }

    /** Reads until the cloud sends {@code Disconnect} (returns its reason) or the connection fails. */
    private Disconnect.Reason readLoop(Connection open) throws IOException {
        while (true) {
            Envelope envelope = open.reader.read();
            switch (envelope.message()) {
                case Ping ping -> send(open, Envelope.of(new Pong(ping.nonce())));
                case Disconnect disconnect -> {
                    LOG.log(Level.INFO, "VectoraCloud closed the connection: {0}", disconnect.reason());
                    return disconnect.reason();
                }
                case ChannelMessage message -> deliver(message);
                case Request request -> answer(open, envelope.requestId(), request);
                case Response response -> {
                    CompletableFuture<Response> future = pending.get(envelope.requestId());
                    if (future != null) {
                        dispatch(() -> future.complete(response));
                    }
                }
                default -> {
                    Message message = envelope.message();
                    if (message instanceof Hello || message instanceof AuthResult) {
                        throw new ProtocolException(message.getClass().getSimpleName() + " after the handshake");
                    }
                    // Pong needs nothing: any frame resets the read timeout already.
                    if (!(message instanceof Pong)) {
                        notifyListeners(new CloudClientEvent.MessageReceived(message));
                    }
                }
            }
        }
    }

    private void deliver(ChannelMessage message) {
        List<ChannelSubscriber> list = subscribers.get(message.channel());
        if (list == null) {
            return;
        }
        for (ChannelSubscriber subscriber : list) {
            dispatch(() -> subscriber.received(message));
        }
    }

    private void answer(Connection open, int requestId, Request request) {
        dispatch(() -> {
            RequestHandler handler = handlers.get(request.channel());
            if (handler == null) {
                send(open, new Envelope(requestId, new Response(Response.Status.NO_HANDLER, EMPTY)));
                return;
            }
            CompletableFuture<Response> result = new CompletableFuture<>();
            try {
                CompletionStage<Response> stage = handler.handle(request);
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
                        Response answer = response;
                        if (error != null || response == null) {
                            LOG.log(Level.WARNING, "Request handler for channel " + request.channel() + " failed",
                                    error);
                            answer = new Response(Response.Status.ERROR, EMPTY);
                        }
                        send(open, new Envelope(requestId, answer));
                    });
        });
    }

    // ---- helpers ----

    private void ping() {
        Connection open = connection;
        if (open != null) {
            send(open, Envelope.of(new Ping(ThreadLocalRandom.current().nextLong())));
        }
    }

    private boolean sendNow(Envelope envelope) {
        Connection open = connection;
        return open != null && send(open, envelope);
    }

    /** Writes a frame; on failure the connection is closed and the reader thread reconnects. */
    private boolean send(Connection target, Envelope envelope) {
        try {
            target.write(codec.encode(envelope));
            return true;
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "Sending to VectoraCloud failed: {0}", e.getMessage());
            target.close();
            return false;
        }
    }

    private void sendQuietly(Connection target, Disconnect.Reason reason) {
        if (target != null && !target.socket.isClosed()) {
            send(target, Envelope.of(new Disconnect(reason)));
        }
    }

    private void failPending(String why) {
        for (Integer id : List.copyOf(pending.keySet())) {
            CompletableFuture<Response> future = pending.remove(id);
            if (future != null) {
                future.completeExceptionally(new NotConnectedException(why));
            }
        }
    }

    private void notifyListeners(CloudClientEvent event) {
        dispatch(() -> {
            for (CloudClientListener listener : listeners) {
                try {
                    listener.onEvent(event);
                } catch (RuntimeException e) {
                    LOG.log(Level.ERROR, "A listener of the cloud client failed", e);
                }
            }
        });
    }

    private void dispatch(Runnable task) {
        try {
            events.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    LOG.log(Level.ERROR, "A listener of the cloud client failed", e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException closing) {
            // The client is closing; late events are dropped.
        }
    }

    /** A counter that skips 0 and wraps to 1, and skips ids that are still open. */
    private int reserveRequestId() {
        while (true) {
            int id = nextRequestId.incrementAndGet() & Integer.MAX_VALUE;
            if (id != Envelope.NO_REQUEST && !pending.containsKey(id)) {
                return id;
            }
        }
    }

    private static int clampCount(int value) {
        return Math.max(0, Math.min(value, MessageLimits.MAX_PLAYERS));
    }

    private static double clamp(double value, double max) {
        return value >= 0 ? Math.min(value, max) : 0;
    }

    private static ThreadFactory daemon(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Handle of a subscription or handler registration; {@link #close()} removes it. */
    @FunctionalInterface
    public interface Subscription extends AutoCloseable {

        /** Ends the subscription or removes the handler. Safe to call more than once. */
        @Override
        void close();
    }

    /** One TCP connection with its reader and a lock for writers. */
    private static final class Connection {

        private final Socket socket;
        private final Object writeLock = new Object();
        private OutputStream out;
        private FrameReader reader;

        Connection(Socket socket) {
            this.socket = socket;
        }

        void open(CloudClientSettings settings, MessageCodec codec) throws IOException {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(settings.host(), settings.port()),
                    (int) settings.connectTimeout().toMillis());
            reader = new FrameReader(socket.getInputStream(), codec);
            synchronized (writeLock) {
                out = new BufferedOutputStream(socket.getOutputStream());
            }
        }

        void write(byte[] frame) throws IOException {
            synchronized (writeLock) {
                if (out == null) {
                    throw new IOException("not connected");
                }
                out.write(frame);
                out.flush();
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Nothing left to do with a socket that cannot be closed.
            }
        }
    }
}
