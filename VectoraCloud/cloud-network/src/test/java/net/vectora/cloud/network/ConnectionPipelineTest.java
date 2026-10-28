package net.vectora.cloud.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import net.vectora.protocol.AuthToken;
import net.vectora.protocol.ChannelName;
import net.vectora.protocol.Protocol;
import net.vectora.protocol.ServiceId;
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

    private static byte[] lengthPrefix(int length) {
        ByteBuffer prefix = ByteBuffer.allocate(VarInts.MAX_BYTES);
        VarInts.write(prefix, length);
        return Arrays.copyOf(prefix.array(), prefix.position());
    }
}
