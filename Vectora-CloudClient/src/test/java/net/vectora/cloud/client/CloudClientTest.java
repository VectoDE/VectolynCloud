package net.vectora.cloud.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import net.vectora.protocol.AuthToken;
import net.vectora.protocol.ChannelName;
import net.vectora.protocol.Protocol;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.VarInts;
import net.vectora.protocol.message.AuthResult;
import net.vectora.protocol.message.Disconnect;
import net.vectora.protocol.message.Envelope;
import net.vectora.protocol.message.Hello;
import net.vectora.protocol.message.Message;
import net.vectora.protocol.message.MessageCodec;
import net.vectora.protocol.message.Ping;
import net.vectora.protocol.message.PlayerJoin;
import net.vectora.protocol.message.ServiceReady;
import net.vectora.protocol.message.ServiceStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The client against a scripted fake cloud on a plain JDK server socket. */
class CloudClientTest {

    private static final ServiceId LOBBY = new ServiceId("lobby-1");
    private static final AuthToken TOKEN = new AuthToken("client-token-not-a-real-secret-01");
    private static final MessageCodec CODEC = MessageCodec.standard();

    private ServerSocket cloud;
    private CloudClient client;

    @BeforeEach
    void openFakeCloud() throws IOException {
        cloud = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        cloud.setSoTimeout(5_000);
    }

    @AfterEach
    void closeAll() throws IOException {
        if (client != null) {
            client.close();
        }
        cloud.close();
    }

    private CloudClient start(Duration idle) {
        CloudClientSettings settings = CloudClientSettings.of("127.0.0.1", cloud.getLocalPort(), LOBBY, TOKEN)
                .withTimeouts(Duration.ofSeconds(2), idle.dividedBy(3), idle, Duration.ofSeconds(1),
                        Duration.ofMillis(20), Duration.ofMillis(100));
        client = CloudClient.create(settings);
        client.start();
        return client;
    }

    @Test
    void saysHelloWithItsIdAndTokenAndThenReportsReady() throws Exception {
        start(Duration.ofSeconds(5));
        try (Peer peer = accept()) {
            Hello hello = (Hello) peer.read().message();
            assertEquals(LOBBY, hello.peer());
            assertTrue(hello.token().matches(TOKEN));
            assertEquals(Protocol.PROTOCOL_VERSION, hello.version());
            peer.send(new AuthResult(AuthResult.Outcome.ACCEPTED, Protocol.PROTOCOL_VERSION));
            awaitConnected();
            assertTrue(client.markReady());
            assertEquals(new ServiceReady(LOBBY), peer.readSkippingPings());
        }
    }

    @Test
    void anOversizedFrameBeforeAuthResultMakesTheClientStartOver() throws Exception {
        start(Duration.ofSeconds(5));
        try (Peer first = accept()) {
            first.read(); // Hello
            ByteBuffer prefix = ByteBuffer.allocate(VarInts.MAX_BYTES);
            VarInts.write(prefix, Protocol.MAX_HANDSHAKE_FRAME_SIZE + 1);
            first.sendRaw(Arrays.copyOf(prefix.array(), prefix.position()));
            assertEquals(new Disconnect(Disconnect.Reason.PROTOCOL_ERROR), first.read().message());
        }
        try (Peer second = accept()) {
            assertInstanceOf(Hello.class, second.read().message());
        }
        assertFalse(client.isConnected());
    }

    @Test
    void aSilentCloudCountsAsGoneAfterTheIdleTimeout() throws Exception {
        start(Duration.ofMillis(600));
        try (Peer first = accept()) {
            first.read();
            first.send(new AuthResult(AuthResult.Outcome.ACCEPTED, Protocol.PROTOCOL_VERSION));
            awaitConnected();
            // The fake cloud never writes again; the client's pings do not keep it alive.
            Message last;
            do {
                last = first.read().message();
            } while (last instanceof Ping);
            assertEquals(new Disconnect(Disconnect.Reason.TIMEOUT), last);
        }
        try (Peer second = accept()) {
            assertInstanceOf(Hello.class, second.read().message());
        }
    }

    @Test
    void statusValuesAreClampedIntoTheProtocolRanges() throws Exception {
        start(Duration.ofSeconds(5));
        try (Peer peer = accept()) {
            peer.read();
            peer.send(new AuthResult(AuthResult.Outcome.ACCEPTED, Protocol.PROTOCOL_VERSION));
            awaitConnected();
            assertTrue(client.reportStatus(-5, 2_000_000, Double.NaN, 1e9));
            ServiceStatus status = (ServiceStatus) peer.readSkippingPings();
            assertEquals(0, status.playersOnline());
            assertEquals(1_000_000, status.maxPlayers());
            assertEquals(0.0, status.tps());
            assertEquals(60_000.0, status.mspt());
            assertTrue(status.heapMax() == ServiceStatus.UNKNOWN_HEAP_MAX || status.heapMax() > 0);
        }
    }

    @Test
    void misuseIsRefusedAndNothingIsSentWhileDisconnected() throws Exception {
        CloudClientSettings settings = CloudClientSettings.of("127.0.0.1", cloud.getLocalPort(), LOBBY, TOKEN);
        client = CloudClient.create(settings);
        assertThrows(IllegalArgumentException.class, () -> client.send(new Ping(1)));
        assertThrows(IllegalArgumentException.class, () -> client.send(new ServiceReady(LOBBY)));
        assertThrows(IllegalArgumentException.class, () -> client.reportStatus(
                new ServiceStatus(new ServiceId("other-1"), 0, 0, 20, 1, 0, 0)));
        assertFalse(client.send(new PlayerJoin(UUID.randomUUID(), "Steve", LOBBY, false)));
        assertFalse(client.publish(new ChannelName("c"), new byte[0]));
        ExecutionException failed = assertThrows(ExecutionException.class,
                () -> client.request(new ChannelName("c"), new byte[0]).get());
        assertInstanceOf(NotConnectedException.class, failed.getCause());
        CloudClient.Subscription handler = client.handleRequests(new ChannelName("c"), request -> null);
        assertThrows(IllegalStateException.class, () -> client.handleRequests(new ChannelName("c"), request -> null));
        handler.close();
        client.handleRequests(new ChannelName("c"), request -> null).close();
    }

    @Test
    void closeIsQuickIdempotentAndFinal() {
        start(Duration.ofSeconds(5));
        client.installShutdownHook();
        client.installShutdownHook();
        long started = System.nanoTime();
        client.close();
        client.close();
        assertTrue(System.nanoTime() - started < Duration.ofSeconds(3).toNanos());
        assertThrows(IllegalStateException.class, client::start);
        assertFalse(client.isConnected());
    }

    private void awaitConnected() throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!client.isConnected()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("client did not connect");
            }
            Thread.sleep(10);
        }
    }

    private Peer accept() throws IOException {
        Socket socket = cloud.accept();
        socket.setSoTimeout(5_000);
        return new Peer(socket);
    }

    /** The fake cloud's side of one connection. */
    private static final class Peer implements AutoCloseable {

        private final Socket socket;
        private final FrameReader reader;
        private final OutputStream out;

        Peer(Socket socket) throws IOException {
            this.socket = socket;
            this.reader = new FrameReader(socket.getInputStream(), CODEC);
            this.reader.authenticated();
            this.out = socket.getOutputStream();
        }

        Envelope read() throws IOException {
            return reader.read();
        }

        Message readSkippingPings() throws IOException {
            Message message;
            do {
                message = read().message();
            } while (message instanceof Ping);
            return message;
        }

        void send(Message message) throws IOException {
            sendRaw(CODEC.encode(Envelope.of(message)));
        }

        void sendRaw(byte[] bytes) throws IOException {
            out.write(bytes);
            out.flush();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
