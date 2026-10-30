package net.vectora.cloud.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.vectora.protocol.AuthToken;
import net.vectora.protocol.ChannelName;
import net.vectora.protocol.GroupName;
import net.vectora.protocol.NodeId;
import net.vectora.protocol.Protocol;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.VarInts;
import net.vectora.protocol.message.AuthResult;
import net.vectora.protocol.message.ChannelMessage;
import net.vectora.protocol.message.Disconnect;
import net.vectora.protocol.message.Envelope;
import net.vectora.protocol.message.Hello;
import net.vectora.protocol.message.MessageCodec;
import net.vectora.protocol.message.Ping;
import net.vectora.protocol.message.PlayerJoin;
import net.vectora.protocol.message.Pong;
import net.vectora.protocol.message.RegisterServer;
import net.vectora.protocol.message.Request;
import net.vectora.protocol.message.Response;
import net.vectora.protocol.message.ServiceReady;
import net.vectora.protocol.message.ServiceStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The server on a real loopback socket, driven by a hand-written peer. */
class CloudNetworkServerTest {

    private static final ServiceId LOBBY = new ServiceId("lobby-1");
    private static final ServiceId SURVIVAL = new ServiceId("survival-1");
    private static final Duration SHORT = Duration.ofSeconds(2);

    private final ServiceTokens tokens = new ServiceTokens();
    private final AuthToken lobbyToken = tokens.issue(LOBBY);
    private final AuthToken survivalToken = tokens.issue(SURVIVAL);
    private final RecordingListener listener = new RecordingListener();
    private CloudNetworkServer server;
    private InetSocketAddress address;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private void start(NetworkSettings settings) throws IOException {
        server = CloudNetworkServer.builder(settings, tokens).listener(listener).build();
        address = server.start();
    }

    private void start() throws IOException {
        start(testSettings());
    }

    private static NetworkSettings testSettings() {
        return NetworkSettings.defaults().withAddress("127.0.0.1", 0)
                .withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofSeconds(1));
    }

    // ---- address and authentication ----

    @Test
    void listensOnLoopbackByDefault() throws IOException {
        NetworkSettings defaults = NetworkSettings.defaults();
        assertEquals("127.0.0.1", defaults.bindHost());
        assertEquals(7010, defaults.port());
        start(defaults.withAddress(defaults.bindHost(), 0));
        assertTrue(address.getAddress().isLoopbackAddress());
    }

    @Test
    void acceptsAServiceWithItsToken() throws Exception {
        start();
        try (TestPeer peer = TestPeer.connect(address)) {
            AuthResult result = peer.hello(LOBBY, lobbyToken);
            assertEquals(AuthResult.Outcome.ACCEPTED, result.outcome());
            assertEquals(Protocol.PROTOCOL_VERSION, result.version());
            assertEquals("connected lobby-1", listener.next());
            assertTrue(server.isConnected(LOBBY));
        }
        assertEquals("disconnected lobby-1 null", listener.next());
        assertFalse(server.isConnected(LOBBY));
    }

    @Test
    void refusesAWrongToken() throws Exception {
        start();
        try (TestPeer peer = TestPeer.connect(address)) {
            // The token of another service is a valid token, just not this one's.
            assertEquals(AuthResult.Outcome.INVALID_TOKEN, peer.hello(LOBBY, survivalToken).outcome());
            assertTrue(peer.closedWithin(SHORT));
        }
        assertFalse(server.isConnected(LOBBY));
        assertNull(listener.poll(200));
    }

    @Test
    void refusesUnknownServicesAndRevokedTokens() throws Exception {
        start();
        try (TestPeer peer = TestPeer.connect(address)) {
            assertEquals(AuthResult.Outcome.UNKNOWN_PEER, peer.hello(new ServiceId("ghost-1"), lobbyToken).outcome());
        }
        // A stale token of an earlier start removes nothing; the current one is removed.
        AuthToken stale = new ServiceTokens().issue(SURVIVAL);
        assertFalse(tokens.remove(SURVIVAL, stale));
        assertEquals(AuthResult.Outcome.ACCEPTED, tokens.authenticate(SURVIVAL, survivalToken));
        assertTrue(tokens.remove(SURVIVAL, survivalToken));
        try (TestPeer peer = TestPeer.connect(address)) {
            assertEquals(AuthResult.Outcome.UNKNOWN_PEER, peer.hello(SURVIVAL, survivalToken).outcome());
        }
        assertNull(listener.poll(200));
    }

    @Test
    void refusesClusterNodesForNow() throws Exception {
        tokens.register(new ServiceId("node-1"), lobbyToken);
        start();
        try (TestPeer peer = TestPeer.connect(address)) {
            assertEquals(AuthResult.Outcome.UNKNOWN_PEER, peer.hello(new NodeId("node-1"), lobbyToken).outcome());
        }
    }

    @Test
    void refusesAnAuthenticatorThatThrows() throws Exception {
        server = CloudNetworkServer.builder(testSettings(), new ServiceAuthenticator() {
            @Override
            public AuthResult.Outcome authenticate(net.vectora.protocol.PeerId peer, AuthToken token) {
                throw new IllegalStateException("broken");
            }

            @Override
            public Admission admit(net.vectora.protocol.PeerId peer, AuthToken token) {
                throw new IllegalStateException("broken");
            }
        }).build();
        address = server.start();
        try (TestPeer peer = TestPeer.connect(address)) {
            assertEquals(AuthResult.Outcome.UNKNOWN_PEER, peer.hello(LOBBY, lobbyToken).outcome());
        }
    }

    // ---- protocol violations before authentication ----

    @Test
    void closesAnOversizedFrameBeforeAuthenticationAtOnce() throws Exception {
        start();
        try (TestPeer peer = TestPeer.connect(address)) {
            // Only the length prefix of a 5000-byte frame: refused before any body byte arrives.
            ByteBuffer prefix = ByteBuffer.allocate(VarInts.MAX_BYTES);
            VarInts.write(prefix, Protocol.MAX_HANDSHAKE_FRAME_SIZE + 1);
            peer.sendRaw(java.util.Arrays.copyOf(prefix.array(), prefix.position()));
            assertEquals(Disconnect.Reason.PROTOCOL_ERROR, peer.readUntil(Disconnect.class).reason());
            assertTrue(peer.closedWithin(SHORT));
        }
    }

    @Test
    void acceptsTheSameFrameSizeAfterAuthentication() throws Exception {
        start();
        BlockingQueue<ChannelMessage> received = new LinkedBlockingQueue<>();
        server.subscribe(new ChannelName("big"), (sender, message) -> received.add(message));
        try (TestPeer peer = TestPeer.authenticated(address, LOBBY, lobbyToken)) {
            byte[] payload = new byte[Protocol.MAX_HANDSHAKE_FRAME_SIZE + 1];
            payload[payload.length - 1] = 7;
            peer.send(new ChannelMessage(new ChannelName("big"), payload));
            ChannelMessage message = received.poll(5, TimeUnit.SECONDS);
            assertArrayEquals(payload, message.payload());
        }
    }

    @Test
    void anythingBeforeHelloIsAViolation() throws Exception {
        start();
        try (TestPeer peer = TestPeer.connect(address)) {
            peer.send(new Ping(1));
            assertEquals(Disconnect.Reason.PROTOCOL_ERROR, peer.readUntil(Disconnect.class).reason());
            assertTrue(peer.closedWithin(SHORT));
        }
    }

    @Test
    void garbageIsAViolation() throws Exception {
        start();
        try (TestPeer peer = TestPeer.connect(address)) {
            peer.sendRaw(new byte[] {4, 1, 0, 0x7F, 0}); // unknown message id 0x7F
            assertEquals(Disconnect.Reason.PROTOCOL_ERROR, peer.readUntil(Disconnect.class).reason());
            assertTrue(peer.closedWithin(SHORT));
        }
    }

    @Test
    void otherMajorVersionIsAnsweredWithIncompatibleVersion() throws Exception {
        start();
        try (TestPeer peer = TestPeer.connect(address)) {
            // Frame header with major version 2; the body does not matter any more.
            peer.sendRaw(new byte[] {4, 2, 0, 0, 0});
            AuthResult result = peer.readUntil(AuthResult.class);
            assertEquals(AuthResult.Outcome.INCOMPATIBLE_VERSION, result.outcome());
            assertTrue(peer.closedWithin(SHORT));
        }
    }

    @Test
    void slowHandshakeIsCutAfterTheHandshakeTimeout() throws Exception {
        start(testSettings().withTimeouts(Duration.ofMillis(500), Duration.ofSeconds(10), Duration.ofSeconds(3),
                Duration.ofSeconds(1)));
        byte[] hello = MessageCodec.standard().encode(Envelope.of(new Hello(Protocol.PROTOCOL_VERSION, LOBBY,
                lobbyToken)));
        long started = System.nanoTime();
        try (TestPeer peer = TestPeer.connect(address)) {
            awaitOpenConnections(1);
            // Slowloris: one byte every 100 ms keeps the connection busy, but the handshake
            // timeout counts from the accept, so Hello never completes in time.
            for (int i = 0; i < hello.length - 1 && server.openConnections() > 0; i++) {
                try {
                    peer.sendRaw(new byte[] {hello[i]});
                } catch (IOException closed) {
                    break;
                }
                Thread.sleep(100);
            }
            awaitOpenConnections(0);
        }
        long elapsed = System.nanoTime() - started;
        assertTrue(elapsed < Duration.ofSeconds(3).toNanos(), "closed after " + elapsed / 1_000_000 + " ms");
        assertFalse(server.isConnected(LOBBY));
        assertNull(listener.poll(100));
    }

    @Test
    void silentNewConnectionGetsTimeoutBeforeItIsClosed() throws Exception {
        start(testSettings().withTimeouts(Duration.ofMillis(300), Duration.ofSeconds(10), Duration.ofSeconds(3),
                Duration.ofSeconds(1)));
        try (TestPeer peer = TestPeer.connect(address)) {
            assertEquals(Disconnect.Reason.TIMEOUT, peer.readUntil(Disconnect.class).reason());
            assertTrue(peer.closedWithin(SHORT));
        }
    }

    @Test
    void secondHelloIsAViolation() throws Exception {
        start();
        try (TestPeer peer = TestPeer.authenticated(address, LOBBY, lobbyToken)) {
            peer.send(new Hello(Protocol.PROTOCOL_VERSION, LOBBY, lobbyToken));
            assertEquals(Disconnect.Reason.PROTOCOL_ERROR, peer.readUntil(Disconnect.class).reason());
            assertTrue(peer.closedWithin(SHORT));
        }
    }

    @Test
    void messagesForTheOtherDirectionAreAViolation() throws Exception {
        start();
        try (TestPeer peer = TestPeer.authenticated(address, LOBBY, lobbyToken)) {
            peer.send(new RegisterServer(SURVIVAL, "127.0.0.1", 30001, new GroupName("survival"), false));
            assertEquals(Disconnect.Reason.PROTOCOL_ERROR, peer.readUntil(Disconnect.class).reason());
        }
    }

    // ---- heartbeat and timeouts ----

    @Test
    void answersPings() throws Exception {
        start();
        try (TestPeer peer = TestPeer.authenticated(address, LOBBY, lobbyToken)) {
            peer.send(new Ping(42));
            assertEquals(42, peer.readUntil(Pong.class).nonce());
        }
    }

    @Test
    void silentServiceIsClosedAfterTheIdleTimeout() throws Exception {
        start(testSettings().withTimeouts(Duration.ofSeconds(2), Duration.ofMillis(800), Duration.ofMillis(200),
                Duration.ofSeconds(1)));
        try (TestPeer peer = TestPeer.authenticated(address, LOBBY, lobbyToken)) {
            assertEquals("connected lobby-1", listener.next());
            // The server pings while it has nothing else to say ...
            peer.readUntil(Ping.class);
            // ... but a peer that never sends anything is dropped.
            assertEquals(Disconnect.Reason.TIMEOUT, peer.readUntil(Disconnect.class).reason());
            assertTrue(peer.closedWithin(SHORT));
        }
        assertEquals("disconnected lobby-1 TIMEOUT", listener.next());
    }

    @Test
    void serviceThatAnswersPingsStaysConnected() throws Exception {
        start(testSettings().withTimeouts(Duration.ofSeconds(2), Duration.ofMillis(800), Duration.ofMillis(200),
                Duration.ofSeconds(1)));
        try (TestPeer peer = TestPeer.authenticated(address, LOBBY, lobbyToken)) {
            long end = System.nanoTime() + Duration.ofMillis(2_500).toNanos();
            while (System.nanoTime() < end) {
                Ping ping = peer.readUntil(Ping.class);
                peer.send(new Pong(ping.nonce()));
            }
            assertTrue(server.isConnected(LOBBY));
        }
    }

    // ---- limits ----

    @Test
    void limitsConnectionsPerAddress() throws Exception {
        start(testSettings().withLimits(10, 2, 16));
        try (TestPeer first = TestPeer.connect(address); TestPeer second = TestPeer.connect(address)) {
            awaitOpenConnections(2);
            try (TestPeer third = TestPeer.connect(address)) {
                assertTrue(third.closedWithin(SHORT), "third connection from the same address");
            }
            // Counter-check: the first two still work.
            assertEquals(AuthResult.Outcome.ACCEPTED, first.hello(LOBBY, lobbyToken).outcome());
            assertEquals(AuthResult.Outcome.ACCEPTED, second.hello(SURVIVAL, survivalToken).outcome());
        }
        awaitOpenConnections(0);
        try (TestPeer again = TestPeer.connect(address)) {
            assertEquals(AuthResult.Outcome.ACCEPTED, again.hello(LOBBY, tokens.issue(LOBBY)).outcome());
        }
    }

    @Test
    void limitsConnectionsInTotal() throws Exception {
        start(testSettings().withLimits(1, 10, 16));
        try (TestPeer first = TestPeer.connect(address)) {
            awaitOpenConnections(1);
            try (TestPeer second = TestPeer.connect(address)) {
                assertTrue(second.closedWithin(SHORT));
            }
            assertEquals(AuthResult.Outcome.ACCEPTED, first.hello(LOBBY, lobbyToken).outcome());
        }
    }

    /** Connections without Hello have their own, smaller limit; an accepted one no longer counts. */
    @Test
    void limitsUnauthenticatedConnectionsSeparately() throws Exception {
        start(testSettings().withLimits(10, 10, 16).withUnauthenticatedLimits(2, 2));
        try (TestPeer first = TestPeer.connect(address); TestPeer second = TestPeer.connect(address)) {
            awaitOpenConnections(2);
            assertEquals(2, server.unauthenticatedConnections());
            try (TestPeer third = TestPeer.connect(address)) {
                assertTrue(third.closedWithin(SHORT), "third connection without Hello");
            }
            assertTrue(server.refusedConnections() >= 1);
            // Once one has authenticated, its slot is free for a new unauthenticated connection.
            assertEquals(AuthResult.Outcome.ACCEPTED, first.hello(LOBBY, lobbyToken).outcome());
            assertEquals(1, server.unauthenticatedConnections());
            try (TestPeer fourth = TestPeer.connect(address)) {
                awaitOpenConnections(3);
                assertEquals(AuthResult.Outcome.ACCEPTED, fourth.hello(SURVIVAL, survivalToken).outcome());
            }
            assertEquals(1, server.unauthenticatedConnections(), "only the second is still silent");
            assertFalse(second.closedWithin(Duration.ofMillis(100)), "the silent one is within the limit");
        }
        awaitOpenConnections(0);
        assertEquals(0, server.unauthenticatedConnections());
    }

    @Test
    void limitsUnauthenticatedConnectionsPerAddress() throws Exception {
        start(testSettings().withLimits(10, 10, 16).withUnauthenticatedLimits(10, 1));
        try (TestPeer first = TestPeer.connect(address)) {
            awaitOpenConnections(1);
            try (TestPeer second = TestPeer.connect(address)) {
                assertTrue(second.closedWithin(SHORT), "second silent connection from the same address");
            }
            assertEquals(AuthResult.Outcome.ACCEPTED, first.hello(LOBBY, lobbyToken).outcome());
        }
    }

    /** A refused Hello frees the slot as well: the connection is closed. */
    @Test
    void aRefusedHelloGivesItsSlotBack() throws Exception {
        start(testSettings().withUnauthenticatedLimits(1, 1));
        for (int i = 0; i < 3; i++) {
            try (TestPeer peer = TestPeer.connect(address)) {
                assertEquals(AuthResult.Outcome.INVALID_TOKEN, peer.hello(LOBBY, survivalToken).outcome());
                assertTrue(peer.closedWithin(SHORT));
            }
            awaitOpenConnections(0);
        }
        assertEquals(0, server.unauthenticatedConnections());
    }

    // ---- tokens are valid for one connection ----

    @Test
    void aCopiedTokenIsRefusedOnARealSocket() throws Exception {
        start();
        try (TestPeer bridge = TestPeer.authenticated(address, LOBBY, lobbyToken)) {
            assertEquals("connected lobby-1", listener.next());
            try (TestPeer thief = TestPeer.connect(address)) {
                assertEquals(AuthResult.Outcome.INVALID_TOKEN, thief.hello(LOBBY, new AuthToken(lobbyToken.value())).outcome());
                assertTrue(thief.closedWithin(SHORT));
            }
            assertTrue(server.isConnected(LOBBY), "the bridge stays connected");
            bridge.send(new Ping(3));
            assertEquals(3, bridge.readUntil(Pong.class).nonce());
        }
    }

    // ---- reports ----

    @Test
    void passesReportsOfTheServiceToTheListener() throws Exception {
        start();
        try (TestPeer peer = TestPeer.authenticated(address, LOBBY, lobbyToken)) {
            assertEquals("connected lobby-1", listener.next());
            peer.send(new ServiceReady(LOBBY));
            peer.send(new ServiceStatus(LOBBY, 3, 100, 20.0, 5.0, 1024, ServiceStatus.UNKNOWN_HEAP_MAX));
            peer.send(new PlayerJoin(java.util.UUID.randomUUID(), "Steve", LOBBY, false));
            assertEquals("ready lobby-1", listener.next());
            assertEquals("status lobby-1 3 heapMax=-1", listener.next());
            assertEquals("message lobby-1 PlayerJoin", listener.next());
        }
    }

    @Test
    void aServiceMayNotReportForAnother() throws Exception {
        start();
        try (TestPeer peer = TestPeer.authenticated(address, LOBBY, lobbyToken)) {
            assertEquals("connected lobby-1", listener.next());
            peer.send(new ServiceStatus(SURVIVAL, 3, 100, 20.0, 5.0, 1024, 2048));
            assertEquals(Disconnect.Reason.PROTOCOL_ERROR, peer.readUntil(Disconnect.class).reason());
        }
        assertEquals("disconnected lobby-1 PROTOCOL_ERROR", listener.next());
    }

    @Test
    void reconnectReplacesTheOlderConnection() throws Exception {
        start();
        try (TestPeer old = TestPeer.authenticated(address, LOBBY, lobbyToken)) {
            assertEquals("connected lobby-1", listener.next());
            // A reconnect presents the ticket of the connection before, not the used-up token.
            try (TestPeer fresh = TestPeer.authenticated(address, LOBBY, old.ticket())) {
                assertEquals("connected lobby-1", listener.next());
                assertEquals(Disconnect.Reason.REPLACED, old.readUntil(Disconnect.class).reason());
                assertTrue(old.closedWithin(SHORT));
                // The service is still connected through the new connection, no disconnect event.
                assertNull(listener.poll(300));
                assertTrue(server.isConnected(LOBBY));
                fresh.send(new Ping(1));
                assertEquals(1, fresh.readUntil(Pong.class).nonce());
            }
        }
        assertEquals("disconnected lobby-1 null", listener.next());
    }

    @Test
    void replacedConnectionIsCutEvenIfItsPeerDoesNotRead() throws Exception {
        start();
        try (TestPeer old = TestPeer.authenticated(address, LOBBY, lobbyToken)) {
            assertEquals("connected lobby-1", listener.next());
            // The old peer reads nothing; fill its socket buffers so Disconnect(REPLACED) cannot be written.
            byte[] payload = new byte[60 * 1024];
            for (int i = 0; i < 256; i++) {
                server.send(LOBBY, new ChannelMessage(new ChannelName("fill"), payload));
            }
            try (TestPeer fresh = TestPeer.authenticated(address, LOBBY, old.ticket())) {
                assertEquals("connected lobby-1", listener.next());
                fresh.send(new Ping(7));
                // Cut after the close grace of one second, long before the idle timeout of ten.
                awaitOpenConnections(1);
                assertTrue(server.isConnected(LOBBY));
            }
            old.abort();
        }
    }

    // ---- requests and messaging ----

    @Test
    void cloudRequestsGetTheirResponse() throws Exception {
        start();
        try (TestPeer peer = TestPeer.authenticated(address, LOBBY, lobbyToken)) {
            CompletableFuture<Response> future = server.request(LOBBY, new Request(new ChannelName("echo"), new byte[] {1}));
            Envelope request = peer.read();
            while (!(request.message() instanceof Request)) {
                request = peer.read();
            }
            assertTrue(request.requestId() > 0);
            peer.send(new Envelope(request.requestId(), new Response(Response.Status.OK, new byte[] {2})));
            Response response = future.get(5, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] {2}, response.payload());
        }
    }

    @Test
    void cloudRequestsTimeOutAndFailOnDisconnect() throws Exception {
        start();
        try (TestPeer peer = TestPeer.authenticated(address, LOBBY, lobbyToken)) {
            CompletableFuture<Response> unanswered = server.request(LOBBY, new Request(new ChannelName("x"), new byte[0]));
            ExecutionException timeout = assertThrows(ExecutionException.class, () -> unanswered.get(5, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, timeout.getCause());

            CompletableFuture<Response> cut = server.request(LOBBY, new Request(new ChannelName("x"), new byte[0]));
            peer.abort();
            ExecutionException closed = assertThrows(ExecutionException.class, () -> cut.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ServiceNotConnectedException.class, closed.getCause());
        }
        CompletableFuture<Response> nobody = server.request(SURVIVAL, new Request(new ChannelName("x"), new byte[0]));
        assertInstanceOf(ServiceNotConnectedException.class,
                assertThrows(ExecutionException.class, nobody::get).getCause());
    }

    @Test
    void serviceRequestsReachTheirHandler() throws Exception {
        start();
        server.handleRequests(new ChannelName("cloud.echo"),
                (service, request) -> CompletableFuture.completedFuture(new Response(Response.Status.OK, request.payload())));
        server.handleRequests(new ChannelName("cloud.broken"), (service, request) -> {
            throw new IllegalStateException("handler bug");
        });
        try (TestPeer peer = TestPeer.authenticated(address, LOBBY, lobbyToken)) {
            peer.send(new Envelope(5, new Request(new ChannelName("cloud.echo"), new byte[] {9})));
            peer.send(new Envelope(6, new Request(new ChannelName("cloud.none"), new byte[0])));
            peer.send(new Envelope(7, new Request(new ChannelName("cloud.broken"), new byte[0])));
            Response[] responses = new Response[8];
            for (int i = 0; i < 3; i++) {
                Envelope envelope = peer.read();
                if (envelope.message() instanceof Response response) {
                    responses[envelope.requestId()] = response;
                } else {
                    i--;
                }
            }
            assertEquals(Response.Status.OK, responses[5].status());
            assertArrayEquals(new byte[] {9}, responses[5].payload());
            assertEquals(Response.Status.NO_HANDLER, responses[6].status());
            assertEquals(Response.Status.ERROR, responses[7].status());
        }
        assertThrows(IllegalStateException.class, () -> server.handleRequests(new ChannelName("cloud.echo"),
                (service, request) -> CompletableFuture.completedFuture(null)));
    }

    @Test
    void channelMessagesGoToOtherServicesAndCloudSubscribers() throws Exception {
        start();
        BlockingQueue<String> cloudSide = new LinkedBlockingQueue<>();
        CloudNetworkServer.Registration registration = server.subscribe(new ChannelName("chat"),
                (sender, message) -> cloudSide.add(sender.value() + ":" + message.payload()[0]));
        try (TestPeer lobby = TestPeer.authenticated(address, LOBBY, lobbyToken);
                TestPeer survival = TestPeer.authenticated(address, SURVIVAL, survivalToken)) {
            awaitConnected(LOBBY, SURVIVAL);
            lobby.send(new ChannelMessage(new ChannelName("chat"), new byte[] {1}));
            assertEquals(1, survival.readUntil(ChannelMessage.class).payload()[0]);
            assertEquals("lobby-1:1", cloudSide.poll(5, TimeUnit.SECONDS));

            // The sender does not get its own message back; a ping shows nothing came before the pong.
            lobby.send(new Ping(99));
            assertInstanceOf(Pong.class, nextNonPing(lobby));

            registration.close();
            server.publish(new ChannelMessage(new ChannelName("chat"), new byte[] {2}));
            assertEquals(2, lobby.readUntil(ChannelMessage.class).payload()[0]);
            assertEquals(2, survival.readUntil(ChannelMessage.class).payload()[0]);
            assertNull(cloudSide.poll(200, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void sendDeliversServerListUpdatesAndRefusesHandshakeMessages() throws Exception {
        start();
        try (TestPeer proxy = TestPeer.authenticated(address, LOBBY, lobbyToken)) {
            awaitConnected(LOBBY);
            RegisterServer register = new RegisterServer(SURVIVAL, "127.0.0.1", 30001, new GroupName("survival"), false);
            assertTrue(server.send(LOBBY, register));
            assertEquals(register, proxy.readUntil(RegisterServer.class));
        }
        assertFalse(server.send(SURVIVAL, new Ping(1)));
        assertThrows(IllegalArgumentException.class,
                () -> server.send(LOBBY, new AuthResult(AuthResult.Outcome.ACCEPTED, Protocol.PROTOCOL_VERSION)));
        assertThrows(IllegalArgumentException.class,
                () -> server.send(LOBBY, new Request(new ChannelName("x"), new byte[0])));
    }

    @Test
    void closeSendsShutdownToEveryService() throws Exception {
        start();
        try (TestPeer peer = TestPeer.authenticated(address, LOBBY, lobbyToken)) {
            awaitConnected(LOBBY);
            server.close();
            assertEquals(Disconnect.Reason.SHUTDOWN, peer.readUntil(Disconnect.class).reason());
            assertTrue(peer.closedWithin(SHORT));
        }
        assertThrows(IllegalStateException.class, () -> server.start());
    }

    @Test
    void portInUseFailsWithIoException() throws Exception {
        start();
        CloudNetworkServer second = CloudNetworkServer.builder(
                testSettings().withAddress("127.0.0.1", address.getPort()), tokens).build();
        try {
            assertThrows(IOException.class, second::start);
        } finally {
            second.close();
        }
    }

    private Object nextNonPing(TestPeer peer) throws IOException {
        while (true) {
            Object message = peer.read().message();
            if (!(message instanceof Ping)) {
                return message;
            }
        }
    }

    private void awaitConnected(ServiceId... services) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        for (ServiceId service : services) {
            while (!server.isConnected(service)) {
                if (System.nanoTime() > end) {
                    throw new AssertionError(service + " did not connect");
                }
                Thread.sleep(10);
            }
        }
    }

    private void awaitOpenConnections(int expected) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (server.openConnections() != expected) {
            if (System.nanoTime() > end) {
                throw new AssertionError("expected " + expected + " open connections, got " + server.openConnections());
            }
            Thread.sleep(10);
        }
    }
}
