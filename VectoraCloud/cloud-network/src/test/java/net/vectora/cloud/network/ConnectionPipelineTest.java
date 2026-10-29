package net.vectora.cloud.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.vectora.protocol.AuthToken;
import net.vectora.protocol.ChannelName;
import net.vectora.protocol.Protocol;
import net.vectora.protocol.ProtocolVersion;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.Message;
import net.vectora.protocol.message.NetworkKick;
import net.vectora.protocol.message.ServerCapacity;
import net.vectora.protocol.message.TransferPlayer;
import net.vectora.protocol.message.UnregisterServer;
import net.vectora.protocol.VarInts;
import net.vectora.protocol.message.AuthResult;
import net.vectora.protocol.message.ChannelMessage;
import net.vectora.protocol.message.Disconnect;
import net.vectora.protocol.message.Envelope;
import net.vectora.protocol.message.Hello;
import net.vectora.protocol.message.MessageCodec;
import org.junit.jupiter.api.Test;

/** The connection state machine without a socket: exact bytes in, exact messages out. */
class ConnectionPipelineTest {

    private static final ServiceId LOBBY = new ServiceId("lobby-1");
    private static final MessageCodec CODEC = MessageCodec.standard();

    private final ServiceTokens tokens = new ServiceTokens();
    private final AuthToken token = tokens.issue(LOBBY);
    private final CloudNetworkServer server = CloudNetworkServer.builder(NetworkSettings.defaults(), tokens).build();

    @Test
    void lengthPrefixAboveTheHandshakeLimitIsRefusedOnItsOwn() {
        try (EmbeddedConnection connection = new EmbeddedConnection(server)) {
            connection.receive(lengthPrefix(Protocol.MAX_HANDSHAKE_FRAME_SIZE + 1));
            List<Envelope> sent = connection.sent();
            assertEquals(List.of(Envelope.of(new Disconnect(Disconnect.Reason.PROTOCOL_ERROR))), sent);
            assertFalse(connection.isOpen());
        }
    }

    @Test
    void sameLengthIsOnlyIncompleteAfterAuthentication() {
        try (EmbeddedConnection connection = new EmbeddedConnection(server)) {
            connection.receive(CODEC.encode(Envelope.of(new Hello(Protocol.PROTOCOL_VERSION, LOBBY, token))));
            connection.receive(lengthPrefix(Protocol.MAX_HANDSHAKE_FRAME_SIZE + 1));
            assertEquals(List.of(Envelope.of(new AuthResult(AuthResult.Outcome.ACCEPTED, Protocol.PROTOCOL_VERSION))),
                    connection.sent());
            assertTrue(connection.isOpen());
        }
    }

    @Test
    void hugeFrameAfterAuthenticationIsStillLimitedToOneMebibyte() {
        try (EmbeddedConnection connection = new EmbeddedConnection(server)) {
            connection.receive(CODEC.encode(Envelope.of(new Hello(Protocol.PROTOCOL_VERSION, LOBBY, token))));
            connection.receive(lengthPrefix(Protocol.MAX_FRAME_SIZE + 1));
            List<Envelope> sent = connection.sent();
            assertEquals(Envelope.of(new Disconnect(Disconnect.Reason.PROTOCOL_ERROR)), sent.get(sent.size() - 1));
            assertFalse(connection.isOpen());
        }
    }

    @Test
    void framesSplitIntoSingleBytesAreReassembled() {
        try (EmbeddedConnection connection = new EmbeddedConnection(server)) {
            byte[] hello = CODEC.encode(Envelope.of(new Hello(Protocol.PROTOCOL_VERSION, LOBBY, token)));
            byte[] message = CODEC.encode(Envelope.of(new ChannelMessage(new ChannelName("c"), new byte[] {1})));
            byte[] both = Arrays.copyOf(hello, hello.length + message.length);
            System.arraycopy(message, 0, both, hello.length, message.length);
            for (byte b : both) {
                connection.receive(new byte[] {b});
            }
            assertEquals(1, connection.sent().size());
            assertTrue(connection.isOpen());
        }
    }

    @Test
    void stopsReadingWhileThePeerDoesNotTakeItsAnswers() {
        try (EmbeddedConnection connection = new EmbeddedConnection(server)) {
            connection.receive(CODEC.encode(Envelope.of(new Hello(Protocol.PROTOCOL_VERSION, LOBBY, token))));
            EmbeddedChannel channel = connection.channel();
            assertTrue(channel.config().isAutoRead());
            // The same signal Netty gives when more than the high water mark is queued.
            channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
            channel.runPendingTasks();
            assertFalse(channel.config().isAutoRead(), "reading stops while the peer is not writable");
            channel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
            channel.runPendingTasks();
            assertTrue(channel.config().isAutoRead(), "reading resumes once the queue has drained");
        }
    }

    /** Only the cloud moves players: a service that sends TransferPlayer or ServerCapacity is cut off. */
    @Test
    void transferAndCapacityFromAServiceAreProtocolViolations() {
        for (Message forbidden : List.of(
                new TransferPlayer(UUID.randomUUID(), new ServiceId("lobby-2"), TransferPlayer.Reason.SCALE_DOWN),
                new ServerCapacity(new ServiceId("lobby-2"), 50, true))) {
            try (EmbeddedConnection connection = new EmbeddedConnection(server)) {
                connection.receive(CODEC.encode(Envelope.of(new Hello(Protocol.PROTOCOL_VERSION, LOBBY, token))));
                connection.receive(CODEC.encode(Envelope.of(forbidden)));
                List<Envelope> sent = connection.sent();
                assertEquals(Envelope.of(new Disconnect(Disconnect.Reason.PROTOCOL_ERROR)), sent.get(sent.size() - 1),
                        forbidden.getClass().getSimpleName());
                assertFalse(connection.isOpen());
            }
        }
        // Counter-check: a message a service may send keeps the connection open.
        try (EmbeddedConnection connection = new EmbeddedConnection(server)) {
            connection.receive(CODEC.encode(Envelope.of(new Hello(Protocol.PROTOCOL_VERSION, LOBBY, token))));
            connection.receive(CODEC.encode(Envelope.of(new ChannelMessage(new ChannelName("c"), new byte[] {1}))));
            assertTrue(connection.isOpen());
        }
    }

    /** A backend reports only its own kicks: a NetworkKick that names another service cuts it off. */
    @Test
    void networkKickOnlyForTheSendersOwnServer() {
        List<ServiceEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        CloudNetworkServer listened = CloudNetworkServer.builder(NetworkSettings.defaults(), tokens)
                .listener(events::add).build();
        NetworkKick own = new NetworkKick(UUID.randomUUID(), LOBBY, "Spam");
        try (EmbeddedConnection connection = new EmbeddedConnection(listened)) {
            connection.receive(CODEC.encode(Envelope.of(new Hello(Protocol.PROTOCOL_VERSION, LOBBY, token))));
            connection.receive(CODEC.encode(Envelope.of(own)));
            assertTrue(connection.isOpen());
            assertTrue(events.contains(new ServiceEvent.MessageReceived(LOBBY, own)), events.toString());
        }
        try (EmbeddedConnection connection = new EmbeddedConnection(listened)) {
            connection.receive(CODEC.encode(Envelope.of(new Hello(Protocol.PROTOCOL_VERSION, LOBBY, token))));
            connection.receive(CODEC.encode(Envelope.of(
                    new NetworkKick(UUID.randomUUID(), new ServiceId("lobby-2"), "Spam"))));
            List<Envelope> sent = connection.sent();
            assertEquals(Envelope.of(new Disconnect(Disconnect.Reason.PROTOCOL_ERROR)), sent.get(sent.size() - 1));
            assertFalse(connection.isOpen());
        }
    }

    /** A 1.0 bridge never gets a message of 1.1; a 1.1 bridge does. */
    @Test
    void messagesOfANewerMinorVersionAreNotSentToAnOlderPeer() {
        Message transfer = new TransferPlayer(UUID.randomUUID(), new ServiceId("lobby-2"), TransferPlayer.Reason.SCALE_DOWN);
        try (EmbeddedConnection connection = new EmbeddedConnection(server)) {
            connection.receive(CODEC.encode(Envelope.of(new Hello(new ProtocolVersion(1, 0), LOBBY, token))));
            connection.sent();
            assertFalse(server.send(LOBBY, transfer));
            assertTrue(server.send(LOBBY, new UnregisterServer(new ServiceId("lobby-2"))));
            connection.channel().runPendingTasks();
            // sent() returns everything written so far: the AuthResult, then only the UnregisterServer.
            assertEquals(List.of(Envelope.of(new UnregisterServer(new ServiceId("lobby-2")))),
                    connection.sent().subList(1, connection.sent().size()));
        }
        try (EmbeddedConnection connection = new EmbeddedConnection(server)) {
            connection.receive(CODEC.encode(Envelope.of(new Hello(Protocol.PROTOCOL_VERSION, LOBBY, token))));
            connection.sent();
            assertTrue(server.send(LOBBY, transfer));
            connection.channel().runPendingTasks();
            assertEquals(List.of(Envelope.of(transfer)), connection.sent().subList(1, connection.sent().size()));
        }
    }

    @Test
    void pipelineCustomizerRunsFirst() {
        CloudNetworkServer customized = CloudNetworkServer.builder(NetworkSettings.defaults(), tokens)
                .pipelineCustomizer(channel -> channel.pipeline().addLast("tls", new ChannelInboundHandlerAdapter()))
                .build();
        try (EmbeddedConnection connection = new EmbeddedConnection(customized)) {
            List<String> names = connection.channel().pipeline().names();
            assertTrue(names.indexOf("tls") >= 0, names.toString());
            assertTrue(names.indexOf("tls") < names.indexOf(CloudNetworkServer.FRAME_DECODER), names.toString());
        }
    }

    private static byte[] lengthPrefix(int length) {
        ByteBuffer prefix = ByteBuffer.allocate(VarInts.MAX_BYTES);
        VarInts.write(prefix, length);
        return Arrays.copyOf(prefix.array(), prefix.position());
    }
}
