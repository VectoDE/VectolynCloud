package net.vectora.protocol.message;

import java.util.List;
import java.util.UUID;
import net.vectora.protocol.AuthToken;
import net.vectora.protocol.ChannelName;
import net.vectora.protocol.GroupName;
import net.vectora.protocol.NodeId;
import net.vectora.protocol.Protocol;
import net.vectora.protocol.ServiceId;

/** Example messages for round-trip tests and fuzz seeds: one typical and one at the limits per type. */
final class SampleMessages {

    static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    static final ServiceId LOBBY = new ServiceId("lobby-1");
    static final ServiceId SURVIVAL = new ServiceId("survival-1");
    static final AuthToken TOKEN = new AuthToken("test-token-not-a-real-secret-000000");

    private SampleMessages() {
    }

    /** One ordinary message of every type in the standard registry. */
    static List<Message> typical() {
        return List.of(
                new Hello(Protocol.PROTOCOL_VERSION, LOBBY, TOKEN),
                new AuthResult(AuthResult.Outcome.ACCEPTED, Protocol.PROTOCOL_VERSION),
                new Ping(42L),
                new Pong(42L),
                new Disconnect(Disconnect.Reason.SHUTDOWN),
                new ServiceReady(LOBBY),
                new ServiceStatus(LOBBY, 12, 100, 19.98, 3.25, 512L << 20, 2048L << 20),
                new RegisterServer(SURVIVAL, "127.0.0.1", 30_002, new GroupName("survival"), false),
                new UnregisterServer(SURVIVAL),
                new PlayerJoin(PLAYER, "Notch", LOBBY, false),
                new PlayerQuit(PLAYER, "Notch", LOBBY, false),
                new PlayerSwitch(PLAYER, ".BedrockSteve", LOBBY, SURVIVAL, true),
                new ChannelMessage(new ChannelName("vectora.chat"), new byte[] {1, 2, 3}),
                new Request(new ChannelName("vectora.lobby.navigator"), new byte[] {4}),
                new Response(Response.Status.OK, new byte[] {5, 6}));
    }

    /** A valid host of exactly {@link MessageLimits#MAX_HOST_LENGTH} characters: labels of 63. */
    static String longestHost() {
        String label = "h".repeat(63);
        String host = label + "." + label + "." + label + "." + "h".repeat(61);
        if (host.length() != MessageLimits.MAX_HOST_LENGTH) {
            throw new AssertionError(host.length());
        }
        return host;
    }

    /** Messages with every length field at its upper limit and numbers at their extremes. */
    static List<Message> atTheLimits() {
        String longName = "N".repeat(MessageLimits.MAX_PLAYER_NAME_LENGTH);
        ServiceId longId = new ServiceId("s".repeat(ServiceId.MAX_LENGTH));
        byte[] fullPayload = new byte[MessageLimits.MAX_PAYLOAD_SIZE];
        ChannelName longChannel = new ChannelName("c".repeat(ChannelName.MAX_LENGTH));
        return List.of(
                new Hello(Protocol.PROTOCOL_VERSION, new NodeId("n".repeat(NodeId.MAX_LENGTH)),
                        new AuthToken("t".repeat(AuthToken.MAX_LENGTH))),
                new Ping(Long.MIN_VALUE),
                new Pong(Long.MAX_VALUE),
                new ServiceStatus(longId, MessageLimits.MAX_PLAYERS, MessageLimits.MAX_PLAYERS,
                        MessageLimits.MAX_TPS, MessageLimits.MAX_MSPT, Long.MAX_VALUE, Long.MAX_VALUE),
                new ServiceStatus(longId, 0, 0, 0.0, 0.0, 0L, 0L),
                new RegisterServer(longId, longestHost(), 65_535,
                        new GroupName("g".repeat(GroupName.MAX_LENGTH)), true),
                new RegisterServer(longId, "::1", 1, new GroupName("g"), false),
                new PlayerJoin(PLAYER, longName, longId, true),
                new PlayerSwitch(PLAYER, "x", LOBBY, longId, false),
                new ChannelMessage(longChannel, fullPayload),
                new ChannelMessage(longChannel, new byte[0]),
                new Request(longChannel, fullPayload),
                new Response(Response.Status.ERROR, fullPayload));
    }

    /** Request id that {@link Envelope} accepts for {@code message}. */
    static int requestIdFor(Message message) {
        return message.requiresRequestId() ? 7 : Envelope.NO_REQUEST;
    }
}
