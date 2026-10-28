package net.vectora.protocol.message;

import static net.vectora.protocol.message.SampleMessages.LOBBY;
import static net.vectora.protocol.message.SampleMessages.PLAYER;
import static net.vectora.protocol.message.SampleMessages.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.vectora.protocol.ChannelName;
import net.vectora.protocol.GroupName;
import net.vectora.protocol.Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Constructor checks of the message records: the limits a sender cannot exceed. */
class MessageValidationTest {

    private static final ChannelName CHANNEL = new ChannelName("vectora.test");
    private static final GroupName GROUP = new GroupName("survival");

    @Test
    void payloadLimitIsSixtyFourKibibytes() {
        assertEquals(64 * 1024, MessageLimits.MAX_PAYLOAD_SIZE);
        byte[] tooLarge = new byte[MessageLimits.MAX_PAYLOAD_SIZE + 1];
        assertThrows(IllegalArgumentException.class, () -> new ChannelMessage(CHANNEL, tooLarge));
        assertThrows(IllegalArgumentException.class, () -> new Request(CHANNEL, tooLarge));
        assertThrows(IllegalArgumentException.class, () -> new Response(Response.Status.OK, tooLarge));
    }

    @Test
    void payloadRecordsCopyTheirBytesAndCompareByContent() {
        byte[] bytes = {1, 2};
        ChannelMessage message = new ChannelMessage(CHANNEL, bytes);
        bytes[0] = 9;
        assertArrayEquals(new byte[] {1, 2}, message.payload());
        message.payload()[1] = 9;
        assertArrayEquals(new byte[] {1, 2}, message.payload());

        assertEquals(message, new ChannelMessage(CHANNEL, new byte[] {1, 2}));
        assertEquals(message.hashCode(), new ChannelMessage(CHANNEL, new byte[] {1, 2}).hashCode());
        assertNotEquals(message, new ChannelMessage(CHANNEL, new byte[] {1, 3}));
        assertEquals(new Request(CHANNEL, new byte[] {1}), new Request(CHANNEL, new byte[] {1}));
        assertEquals(new Response(Response.Status.OK, new byte[] {1}), new Response(Response.Status.OK, new byte[] {1}));
    }

    @Test
    void payloadIsNotPrinted() {
        byte[] secret = "password".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(new ChannelMessage(CHANNEL, secret).toString().contains("password"));
        assertFalse(new Request(CHANNEL, secret).toString().contains("password"));
        assertFalse(new Response(Response.Status.OK, secret).toString().contains("password"));
        assertTrue(new ChannelMessage(CHANNEL, secret).toString().contains("8 bytes"));
    }

    @Test
    void helloDoesNotPrintTheToken() {
        Hello hello = new Hello(Protocol.PROTOCOL_VERSION, LOBBY, SampleMessages.TOKEN);
        assertFalse(hello.toString().contains(SampleMessages.TOKEN.value()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65_536})
    void rejectsInvalidPorts(int port) {
        assertThrows(IllegalArgumentException.class, () -> new RegisterServer(SURVIVAL, "localhost", port, GROUP, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "local host", "host/path", "höst", "[::1]"})
    void rejectsInvalidHosts(String host) {
        assertThrows(IllegalArgumentException.class, () -> new RegisterServer(SURVIVAL, host, 25_565, GROUP, false));
    }

    @Test
    void hostLengthLimit() {
        String tooLong = "h".repeat(MessageLimits.MAX_HOST_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> new RegisterServer(SURVIVAL, tooLong, 25_565, GROUP, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " Steve", "Steve ", "Ste\nve", "Stéve"})
    void rejectsInvalidPlayerNames(String name) {
        assertThrows(IllegalArgumentException.class, () -> new PlayerJoin(PLAYER, name, LOBBY, false));
    }

    @Test
    void playerNameLengthLimitAndBedrockNames() {
        String tooLong = "n".repeat(MessageLimits.MAX_PLAYER_NAME_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> new PlayerQuit(PLAYER, tooLong, LOBBY, false));
        // Floodgate prefixes Bedrock names and may keep spaces inside them.
        assertEquals(".Bedrock Steve", new PlayerJoin(PLAYER, ".Bedrock Steve", LOBBY, true).name());
    }

    @Test
    void switchNeedsTwoDifferentServices() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerSwitch(PLAYER, "Steve", LOBBY, LOBBY, false));
    }

    @Test
    void statusRejectsImpossibleNumbers() {
        assertThrows(IllegalArgumentException.class, () -> status(-1, 10, 20, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> status(1, -1, 20, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> status(MessageLimits.MAX_PLAYERS + 1, 10, 20, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> status(1, 10, Double.NaN, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> status(1, 10, Double.POSITIVE_INFINITY, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> status(1, 10, -0.5, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> status(1, 10, MessageLimits.MAX_TPS + 1, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> status(1, 10, 20, Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> status(1, 10, 20, MessageLimits.MAX_MSPT + 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> status(1, 10, 20, 1, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> status(1, 10, 20, 1, 0, -1));
    }

    @Test
    void envelopeEnforcesRequestIdRules() {
        assertThrows(IllegalArgumentException.class, () -> new Envelope(0, new Request(CHANNEL, new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> new Envelope(0, new Response(Response.Status.OK, new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> new Envelope(3, new Ping(1)));
        assertThrows(IllegalArgumentException.class, () -> new Envelope(-1, new Request(CHANNEL, new byte[0])));
        assertEquals(Envelope.NO_REQUEST, Envelope.of(new Ping(1)).requestId());
        assertEquals(5, new Envelope(5, new Request(CHANNEL, new byte[0])).requestId());
    }

    @Test
    void pongAnswersAPing() {
        assertEquals(new Pong(99), Pong.answering(new Ping(99)));
    }

    @Test
    void wireCodesAreStable() {
        assertEquals(0, AuthResult.Outcome.ACCEPTED.code());
        assertEquals(3, AuthResult.Outcome.INCOMPATIBLE_VERSION.code());
        assertEquals(0, Disconnect.Reason.NORMAL.code());
        assertEquals(4, Disconnect.Reason.PROTOCOL_ERROR.code());
        assertEquals(0, Response.Status.OK.code());
    }

    private static ServiceStatus status(int online, int max, double tps, double mspt, long heapUsed, long heapMax) {
        return new ServiceStatus(LOBBY, online, max, tps, mspt, heapUsed, heapMax);
    }
}
