package net.vectora.protocol.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.function.Consumer;
import net.vectora.protocol.ChannelName;
import net.vectora.protocol.Frame;
import net.vectora.protocol.FrameCodec;
import net.vectora.protocol.IncompatibleVersionException;
import net.vectora.protocol.PacketWriter;
import net.vectora.protocol.Protocol;
import net.vectora.protocol.ProtocolException;
import net.vectora.protocol.ProtocolVersion;
import net.vectora.protocol.ServiceId;
import org.junit.jupiter.api.Test;

/** Decoding of crafted frames that a well-behaved sender would never produce. */
class MessageCodecTest {

    private final MessageCodec codec = MessageCodec.standard();

    @Test
    void unknownMessageIdIsRejected() {
        assertThrows(ProtocolException.class, () -> codec.fromFrame(frame(0x7F, 0, out -> { })));
    }

    @Test
    void leftoverPayloadBytesAreRejected() {
        assertThrows(ProtocolException.class, () -> codec.fromFrame(frame(0x02, 0, out -> out.writeLong(1).writeByte(0))));
    }

    @Test
    void missingPayloadBytesAreRejected() {
        assertThrows(ProtocolException.class, () -> codec.fromFrame(frame(0x02, 0, out -> out.writeInt(1))));
    }

    @Test
    void otherMajorVersionIsReportedWithTheRemoteVersion() {
        Frame frame = new Frame(new ProtocolVersion(2, 0), 0x02, 0, new PacketWriter().writeLong(1).toByteArray());
        IncompatibleVersionException e = assertThrows(IncompatibleVersionException.class, () -> codec.fromFrame(frame));
        assertEquals(new ProtocolVersion(2, 0), e.remoteVersion());
    }

    @Test
    void newerMinorVersionOfTheSameMajorIsAccepted() {
        Frame frame = new Frame(new ProtocolVersion(1, 9), 0x02, 0, new PacketWriter().writeLong(1).toByteArray());
        assertEquals(Envelope.of(new Ping(1)), codec.fromFrame(frame));
    }

    @Test
    void requestIdRulesAreCheckedOnDecode() {
        byte[] request = new PacketWriter().writeString("vectora.x", ChannelName.MAX_LENGTH).writeByteArray(new byte[0], 1)
                .toByteArray();
        assertThrows(ProtocolException.class, () -> codec.fromFrame(new Frame(Protocol.PROTOCOL_VERSION, 0x50, 0, request)));
        assertThrows(ProtocolException.class, () -> codec.fromFrame(frame(0x02, 9, out -> out.writeLong(1))));
    }

    @Test
    void constructorRejectionsBecomeProtocolExceptions() {
        assertThrows(ProtocolException.class, () -> codec.fromFrame(frame(0x11, 0, out -> out
                .writeString("Lobby-1", ServiceId.MAX_LENGTH)
                .writeVarInt(1).writeVarInt(10)
                .writeDouble(Double.NaN).writeDouble(1)
                .writeVarLong(0).writeVarLong(0))));
        assertThrows(ProtocolException.class, () -> codec.fromFrame(frame(0x10, 0,
                out -> out.writeString("bad id", ServiceId.MAX_LENGTH))));
    }

    @Test
    void lengthsAboveTheLimitsAreRejectedOnTheWire() {
        int tooLarge = MessageLimits.MAX_PAYLOAD_SIZE + 1;
        assertThrows(ProtocolException.class, () -> codec.fromFrame(frame(0x40, 0, out -> out
                .writeString("vectora.x", ChannelName.MAX_LENGTH).writeVarInt(tooLarge).writeBytes(new byte[tooLarge]))));

        String longName = "n".repeat(MessageLimits.MAX_PLAYER_NAME_LENGTH + 1);
        assertThrows(ProtocolException.class, () -> codec.fromFrame(frame(0x30, 0, out -> out
                .writeUuid(SampleMessages.PLAYER).writeString(longName, 64)
                .writeString("Lobby-1", ServiceId.MAX_LENGTH).writeBoolean(false))));

        String longHost = "h".repeat(MessageLimits.MAX_HOST_LENGTH + 1);
        assertThrows(ProtocolException.class, () -> codec.fromFrame(frame(0x20, 0, out -> out
                .writeString("Lobby-1", ServiceId.MAX_LENGTH).writeString(longHost, 300)
                .writeShort(25_565).writeString("Lobby", 32).writeBoolean(false))));

        String longId = "a".repeat(ServiceId.MAX_LENGTH + 1);
        assertThrows(ProtocolException.class, () -> codec.fromFrame(frame(0x10, 0, out -> out.writeString(longId, 100))));
    }

    @Test
    void unknownEnumCodesAreRejected() {
        assertThrows(ProtocolException.class, () -> codec.fromFrame(frame(0x04, 0, out -> out.writeVarInt(999))));
        assertThrows(ProtocolException.class, () -> codec.fromFrame(frame(0x01, 0, out -> out.writeVarInt(77).writeByte(1).writeByte(0))));
        assertThrows(ProtocolException.class, () -> codec.fromFrame(frame(0x51, 1, out -> out.writeVarInt(-3).writeVarInt(0))));
    }

    @Test
    void streamDecodingReturnsMessagesInOrder() {
        Envelope first = Envelope.of(new Ping(1));
        Envelope second = new Envelope(8, new Response(Response.Status.OK, new byte[] {1}));
        ByteBuffer stream = ByteBuffer.allocate(128);
        stream.put(codec.encode(first)).put(codec.encode(second)).flip();

        assertEquals(Optional.of(first), codec.tryDecode(stream));
        assertEquals(Optional.of(second), codec.tryDecode(stream));
        assertEquals(Optional.empty(), codec.tryDecode(stream));
    }

    @Test
    void framesCarryTheCurrentVersionAndTheRegisteredId() {
        Frame frame = codec.toFrame(Envelope.of(new Ping(5)));
        assertEquals(Protocol.PROTOCOL_VERSION, frame.version());
        assertEquals(0x02, frame.messageId());
        assertEquals(0, frame.requestId());
        assertEquals(Envelope.of(new Ping(5)), codec.fromFrame(FrameCodec.decode(FrameCodec.encode(frame))));
    }

    private static Frame frame(int messageId, int requestId, Consumer<PacketWriter> payload) {
        PacketWriter out = new PacketWriter();
        payload.accept(out);
        return new Frame(Protocol.PROTOCOL_VERSION, messageId, requestId, out.toByteArray());
    }
}
