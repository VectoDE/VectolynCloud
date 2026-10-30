package net.vectora.cloud.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.vectora.protocol.AuthToken;
import net.vectora.protocol.ChannelName;
import net.vectora.protocol.GroupName;
import net.vectora.protocol.Protocol;
import net.vectora.protocol.ProtocolVersion;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.Message;
import net.vectora.protocol.message.NetworkKick;
import net.vectora.protocol.message.RegisterServer;
import net.vectora.protocol.message.ServerCapacity;
import net.vectora.protocol.message.ServiceReady;
import net.vectora.protocol.message.TransferPlayer;
import net.vectora.protocol.message.UnregisterServer;
import net.vectora.protocol.VarInts;
import net.vectora.protocol.message.AuthResult;
import net.vectora.protocol.message.ChannelMessage;
import net.vectora.protocol.message.Disconnect;
import net.vectora.protocol.message.Envelope;
import net.vectora.protocol.message.Hello;
import net.vectora.protocol.message.MessageCodec;
import net.vectora.protocol.message.SessionTicket;
import org.junit.jupiter.api.Test;

/** The connection state machine without a socket: exact bytes in, exact messages out. */
class ConnectionPipelineTest {

    private static final ServiceId LOBBY = new ServiceId("lobby-1");
    private static final MessageCodec CODEC = MessageCodec.standard();

    /** Each connection of a test gets a fresh token of its own: a token is valid for one connection. */
    private final ServiceTokens tokens = new ServiceTokens();
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
            connection.receive(CODEC.encode(Envelope.of(new Hello(Protocol.PROTOCOL_VERSION, LOBBY, tokens.issue(LOBBY)))));
            connection.receive(lengthPrefix(Protocol.MAX_HANDSHAKE_FRAME_SIZE + 1));
            List<Envelope> sent = connection.sent();
            assertEquals(2, sent.size(), sent.toString());
            assertEquals(Envelope.of(new AuthResult(AuthResult.Outcome.ACCEPTED, Protocol.PROTOCOL_VERSION)), sent.get(0));
            assertInstanceOf(SessionTicket.class, sent.get(1).message());
            assertTrue(connection.isOpen());
        }
    }

    @Test
    void hugeFrameAfterAuthenticationIsStillLimitedToOneMebibyte() {
        try (EmbeddedConnection connection = new EmbeddedConnection(server)) {
            connection.receive(CODEC.encode(Envelope.of(new Hello(Protocol.PROTOCOL_VERSION, LOBBY, tokens.issue(LOBBY)))));
            connection.receive(lengthPrefix(Protocol.MAX_FRAME_SIZE + 1));
            List<Envelope> sent = connection.sent();
            assertEquals(Envelope.of(new Disconnect(Disconnect.Reason.PROTOCOL_ERROR)), sent.get(sent.size() - 1));
            assertFalse(connection.isOpen());
        }
    }

    @Test
    void framesSplitIntoSingleBytesAreReassembled() {
        try (EmbeddedConnection connection = new EmbeddedConnection(server)) {
            byte[] hello = CODEC.encode(Envelope.of(new Hello(Protocol.PROTOCOL_VERSION, LOBBY, tokens.issue(LOBBY))));
            byte[] message = CODEC.encode(Envelope.of(new ChannelMessage(new ChannelName("c"), new byte[] {1})));
            byte[] both = Arrays.copyOf(hello, hello.length + message.length);
            System.arraycopy(message, 0, both, hello.length, message.length);
            for (byte b : both) {
                connection.receive(new byte[] {b});
            }
            assertEquals(2, connection.sent().size(), "AuthResult and SessionTicket");
            assertTrue(connection.isOpen());
        }
    }

    @Test
    void stopsReadingWhileThePeerDoesNotTakeItsAnswers() {
        try (EmbeddedConnection connection = new EmbeddedConnection(server)) {
            connection.receive(CODEC.encode(Envelope.of(new Hello(Protocol.PROTOCOL_VERSION, LOBBY, tokens.issue(LOBBY)))));
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

    /**
     * Only the cloud moves players and fills the proxies' server lists: a service that sends
     * TransferPlayer, ServerCapacity, RegisterServer or UnregisterServer is cut off, so no backend
     * can reach a proxy with them (rights audit, docs/cloud/rights-audit.md).
     */
    @Test
    void transferCapacityAndServerListsFromAServiceAreProtocolViolations() {
        for (Message forbidden : List.of(
                new TransferPlayer(UUID.randomUUID(), new ServiceId("lobby-2"), TransferPlayer.Reason.SCALE_DOWN),
                new ServerCapacity(new ServiceId("lobby-2"), 50, true),
                new RegisterServer(new ServiceId("lobby-2"), "127.0.0.1", 30001, new GroupName("lobby"), false),
                new UnregisterServer(new ServiceId("lobby-2")))) {
            try (EmbeddedConnection connection = new EmbeddedConnection(server)) {
                connection.receive(CODEC.encode(Envelope.of(new Hello(Protocol.PROTOCOL_VERSION, LOBBY, tokens.issue(LOBBY)))));
                connection.receive(CODEC.encode(Envelope.of(forbidden)));
                List<Envelope> sent = connection.sent();
                assertEquals(Envelope.of(new Disconnect(Disconnect.Reason.PROTOCOL_ERROR)), sent.get(sent.size() - 1),
                        forbidden.getClass().getSimpleName());
                assertFalse(connection.isOpen());
            }
        }
        // Counter-check: a message a service may send keeps the connection open.
        try (EmbeddedConnection connection = new EmbeddedConnection(server)) {
            connection.receive(CODEC.encode(Envelope.of(new Hello(Protocol.PROTOCOL_VERSION, LOBBY, tokens.issue(LOBBY)))));
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
            connection.receive(CODEC.encode(Envelope.of(new Hello(Protocol.PROTOCOL_VERSION, LOBBY, tokens.issue(LOBBY)))));
            connection.receive(CODEC.encode(Envelope.of(own)));
            assertTrue(connection.isOpen());
            assertTrue(events.contains(new ServiceEvent.MessageReceived(LOBBY, own)), events.toString());
        }
        try (EmbeddedConnection connection = new EmbeddedConnection(listened)) {
            connection.receive(CODEC.encode(Envelope.of(new Hello(Protocol.PROTOCOL_VERSION, LOBBY, tokens.issue(LOBBY)))));
            connection.receive(CODEC.encode(Envelope.of(
                    new NetworkKick(UUID.randomUUID(), new ServiceId("lobby-2"), "Spam"))));
            List<Envelope> sent = connection.sent();
            assertEquals(Envelope.of(new Disconnect(Disconnect.Reason.PROTOCOL_ERROR)), sent.get(sent.size() - 1));
            assertFalse(connection.isOpen());
        }
    }

    /** A service reports only that it is ready itself: a ServiceReady for another service cuts it off. */
    @Test
    void readyOnlyForTheSenderItself() {
        try (EmbeddedConnection connection = new EmbeddedConnection(server)) {
            connection.receive(CODEC.encode(Envelope.of(new Hello(Protocol.PROTOCOL_VERSION, LOBBY, tokens.issue(LOBBY)))));
            connection.receive(CODEC.encode(Envelope.of(new ServiceReady(LOBBY))));
            assertTrue(connection.isOpen(), "counter-check: its own ready");
            connection.receive(CODEC.encode(Envelope.of(new ServiceReady(new ServiceId("lobby-2")))));
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
            connection.receive(CODEC.encode(Envelope.of(new Hello(new ProtocolVersion(1, 0), LOBBY, tokens.issue(LOBBY)))));
            connection.sent();
            assertFalse(server.send(LOBBY, transfer));
            assertTrue(server.send(LOBBY, new UnregisterServer(new ServiceId("lobby-2"))));
            connection.channel().runPendingTasks();
            // sent() returns everything written so far: the AuthResult, then only the UnregisterServer.
            assertEquals(List.of(Envelope.of(new UnregisterServer(new ServiceId("lobby-2")))),
                    connection.sent().subList(1, connection.sent().size()));
        }
        try (EmbeddedConnection connection = new EmbeddedConnection(server)) {
            connection.receive(CODEC.encode(Envelope.of(new Hello(Protocol.PROTOCOL_VERSION, LOBBY, tokens.issue(LOBBY)))));
            connection.sent();
            assertTrue(server.send(LOBBY, transfer));
            connection.channel().runPendingTasks();
            // AuthResult and SessionTicket, then the transfer.
            assertEquals(List.of(Envelope.of(transfer)), connection.sent().subList(2, connection.sent().size()));
        }
    }

    // ---- tokens are valid for one connection (issue #73) ----

    /** The start token sits in the environment of the server process: once used, a copy of it is worthless. */
    @Test
    void aReusedOrCopiedStartTokenIsRefused() {
        AuthToken start = tokens.issue(LOBBY);
        try (EmbeddedConnection first = new EmbeddedConnection(server)) {
            assertEquals(AuthResult.Outcome.ACCEPTED, hello(first, Protocol.PROTOCOL_VERSION, start).outcome());
            assertTrue(first.isOpen());
            // A copy of the token, while the first connection is still open and after it.
            AuthToken copy = new AuthToken(start.value());
            try (EmbeddedConnection second = new EmbeddedConnection(server)) {
                assertEquals(AuthResult.Outcome.INVALID_TOKEN, hello(second, Protocol.PROTOCOL_VERSION, copy).outcome());
                assertFalse(second.isOpen());
            }
            assertTrue(first.isOpen(), "the refused copy does not touch the open connection");
        }
        try (EmbeddedConnection third = new EmbeddedConnection(server)) {
            assertEquals(AuthResult.Outcome.INVALID_TOKEN, hello(third, Protocol.PROTOCOL_VERSION, start).outcome());
        }
    }

    /** Each acceptance hands out a new ticket; a ticket gets in once, then it is refused like the token. */
    @Test
    void theTicketOpensExactlyOneNextConnection() {
        AuthToken start = tokens.issue(LOBBY);
        AuthToken ticket;
        try (EmbeddedConnection first = new EmbeddedConnection(server)) {
            hello(first, Protocol.PROTOCOL_VERSION, start);
            ticket = ticketOf(first);
        }
        assertFalse(ticket.matches(start));
        AuthToken next;
        try (EmbeddedConnection second = new EmbeddedConnection(server)) {
            assertEquals(AuthResult.Outcome.ACCEPTED, hello(second, Protocol.PROTOCOL_VERSION, ticket).outcome());
            next = ticketOf(second);
        }
        assertFalse(next.matches(ticket));
        try (EmbeddedConnection replay = new EmbeddedConnection(server)) {
            assertEquals(AuthResult.Outcome.INVALID_TOKEN, hello(replay, Protocol.PROTOCOL_VERSION, ticket).outcome());
        }
        try (EmbeddedConnection old = new EmbeddedConnection(server)) {
            assertEquals(AuthResult.Outcome.INVALID_TOKEN, hello(old, Protocol.PROTOCOL_VERSION, start).outcome());
        }
        try (EmbeddedConnection third = new EmbeddedConnection(server)) {
            assertEquals(AuthResult.Outcome.ACCEPTED, hello(third, Protocol.PROTOCOL_VERSION, next).outcome());
        }
        // The node removes the start by the token it issued; that also ends every ticket of it.
        assertTrue(tokens.remove(LOBBY, start));
        assertEquals(AuthResult.Outcome.UNKNOWN_PEER, tokens.authenticate(LOBBY, next));
    }

    /** Saying an older protocol version does not keep the token reusable: it is used up all the same. */
    @Test
    void anOlderPeerGetsNoTicketButStillUsesItsTokenUp() {
        AuthToken start = tokens.issue(LOBBY);
        try (EmbeddedConnection old = new EmbeddedConnection(server)) {
            assertEquals(AuthResult.Outcome.ACCEPTED, hello(old, new ProtocolVersion(1, 1), start).outcome());
            assertEquals(1, old.sent().size(), "no SessionTicket for a 1.1 peer");
        }
        try (EmbeddedConnection again = new EmbeddedConnection(server)) {
            assertEquals(AuthResult.Outcome.INVALID_TOKEN, hello(again, new ProtocolVersion(1, 1), start).outcome());
        }
    }

    /** Only the cloud hands out tickets. */
    @Test
    void aTicketFromAServiceIsAViolation() {
        try (EmbeddedConnection connection = new EmbeddedConnection(server)) {
            hello(connection, Protocol.PROTOCOL_VERSION, tokens.issue(LOBBY));
            connection.receive(CODEC.encode(Envelope.of(new SessionTicket(new AuthToken("forged-ticket-0123456789")))));
            List<Envelope> sent = connection.sent();
            assertEquals(Envelope.of(new Disconnect(Disconnect.Reason.PROTOCOL_ERROR)), sent.get(sent.size() - 1));
            assertFalse(connection.isOpen());
        }
    }

    /** A ticket that never arrived: the connection closes before the peer sends anything after it. */
    @Test
    void aTicketThatWasNotTakenGivesTheTokenBack() {
        AuthToken start = tokens.issue(LOBBY);
        AuthToken lost;
        try (EmbeddedConnection first = new EmbeddedConnection(server)) {
            first.receive(CODEC.encode(Envelope.of(new Hello(Protocol.PROTOCOL_VERSION, LOBBY, start))));
            lost = ticketOf(first);
        }
        assertEquals(AuthResult.Outcome.INVALID_TOKEN, tokens.authenticate(LOBBY, lost), "the lost ticket is void");
        assertEquals(AuthResult.Outcome.ACCEPTED, tokens.authenticate(LOBBY, start), "the token is valid again");
        // Counter-check: once a frame followed the ticket, closing gives nothing back.
        AuthToken taken;
        try (EmbeddedConnection second = new EmbeddedConnection(server)) {
            hello(second, Protocol.PROTOCOL_VERSION, start);
            taken = ticketOf(second);
        }
        assertEquals(AuthResult.Outcome.INVALID_TOKEN, tokens.authenticate(LOBBY, start));
        assertEquals(AuthResult.Outcome.ACCEPTED, tokens.authenticate(LOBBY, taken));
    }

    /** A rollback only touches the ticket it is about: a newer ticket stays. */
    @Test
    void aLateRollbackDoesNotUndoANewerTicket() {
        AuthToken start = tokens.issue(LOBBY);
        ServiceAuthenticator.Admission first = tokens.admit(LOBBY, start);
        ServiceAuthenticator.Admission second = tokens.admit(LOBBY, first.ticket());
        tokens.ticketNotTaken(LOBBY, start, first.ticket());
        assertEquals(AuthResult.Outcome.INVALID_TOKEN, tokens.authenticate(LOBBY, start));
        assertEquals(AuthResult.Outcome.ACCEPTED, tokens.authenticate(LOBBY, second.ticket()));
    }

    /** Hello as a client sends it, followed by a Ping once the ticket is there (the client's confirmation). */
    private static AuthResult hello(EmbeddedConnection connection, ProtocolVersion version, AuthToken token) {
        connection.receive(CODEC.encode(Envelope.of(new Hello(version, LOBBY, token))));
        List<Envelope> sent = connection.sent();
        if (sent.size() > 1 && sent.get(1).message() instanceof SessionTicket) {
            connection.receive(CODEC.encode(Envelope.of(new net.vectora.protocol.message.Ping(0))));
        }
        return (AuthResult) sent.get(0).message();
    }

    private static AuthToken ticketOf(EmbeddedConnection connection) {
        return ((SessionTicket) connection.sent().get(1).message()).ticket();
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
