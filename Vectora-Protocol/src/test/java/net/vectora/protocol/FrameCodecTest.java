package net.vectora.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FrameCodecTest {

    private static final ProtocolVersion V1 = new ProtocolVersion(1, 0);

    @Test
    void encodesTheDocumentedLayout() {
        Frame frame = new Frame(V1, 0x11, 300, new byte[] {7, 8});
        // length 7 | major 1 | minor 0 | message id 0x11 | request id 300 = AC 02 | payload 07 08
        byte[] expected = {7, 1, 0, 0x11, (byte) 0xAC, 0x02, 7, 8};
        assertArrayEquals(expected, FrameCodec.encode(frame));
    }

    @Test
    void roundTrips() {
        Frame frame = new Frame(new ProtocolVersion(1, 3), 5, 0, new byte[] {1, 2, 3});
        assertEquals(frame, FrameCodec.decode(FrameCodec.encode(frame)));
    }

    @Test
    void largestFrameIsAcceptedOneMoreByteIsNot() {
        int header = FrameCodec.MIN_BODY_SIZE;
        Frame largest = new Frame(V1, 0, 0, new byte[Protocol.MAX_FRAME_SIZE - header]);
        assertEquals(Protocol.MAX_FRAME_SIZE, largest.bodySize());
        assertEquals(largest, FrameCodec.decode(FrameCodec.encode(largest)));

        assertThrows(IllegalArgumentException.class,
                () -> new Frame(V1, 0, 0, new byte[Protocol.MAX_FRAME_SIZE - header + 1]));
    }

    @Test
    void rejectsADeclaredLengthAboveTheLimitBeforeTheBodyArrives() {
        ByteBuffer prefixOnly = ByteBuffer.allocate(VarInts.MAX_BYTES);
        VarInts.write(prefixOnly, Protocol.MAX_FRAME_SIZE + 1);
        prefixOnly.flip();
        assertThrows(ProtocolException.class, () -> FrameCodec.tryDecode(prefixOnly));
    }

    @Test
    void rejectsBodiesShorterThanTheHeader() {
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(new byte[] {3, 1, 0, 0}));
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(new byte[] {0}));
    }

    @Test
    void rejectsNegativeIds() {
        byte[] negativeMessageId = {8, 1, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F, 0};
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(negativeMessageId));
        byte[] negativeRequestId = {8, 1, 0, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F};
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(negativeRequestId));
        assertThrows(IllegalArgumentException.class, () -> new Frame(V1, -1, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new Frame(V1, 0, -1, new byte[0]));
    }

    @Test
    void rejectsTrailingAndMissingBytes() {
        byte[] encoded = FrameCodec.encode(new Frame(V1, 1, 0, new byte[] {9}));
        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(trailing));
        byte[] truncated = Arrays.copyOf(encoded, encoded.length - 1);
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(truncated));
    }

    @Test
    void tryDecodeWaitsForMoreBytesWithoutConsumingThem() {
        byte[] encoded = FrameCodec.encode(new Frame(V1, 1, 0, new byte[200]));
        for (int cut = 0; cut < encoded.length; cut++) {
            ByteBuffer partial = ByteBuffer.wrap(encoded, 0, cut);
            assertEquals(Optional.empty(), FrameCodec.tryDecode(partial), "cut at " + cut);
            assertEquals(0, partial.position(), "cut at " + cut);
        }
    }

    @Test
    void tryDecodeReadsFramesBackToBack() {
        Frame first = new Frame(V1, 1, 0, new byte[] {1});
        Frame second = new Frame(V1, 2, 4, new byte[0]);
        ByteBuffer stream = ByteBuffer.allocate(64);
        stream.put(FrameCodec.encode(first)).put(FrameCodec.encode(second)).put((byte) 0x05).flip();

        assertEquals(Optional.of(first), FrameCodec.tryDecode(stream));
        assertEquals(Optional.of(second), FrameCodec.tryDecode(stream));
        assertEquals(Optional.empty(), FrameCodec.tryDecode(stream));
        assertEquals(1, stream.remaining());
    }

    @Test
    void frameKeepsItsPayloadPrivate() {
        byte[] payload = {1, 2};
        Frame frame = new Frame(V1, 1, 0, payload);
        payload[0] = 9;
        assertEquals(1, frame.payload()[0]);
        frame.payload()[1] = 9;
        assertEquals(2, frame.payload()[1]);
    }

    @Test
    void frameToStringHidesThePayload() {
        Frame frame = new Frame(V1, 1, 0, "secret".getBytes(StandardCharsets.UTF_8));
        assertFalse(frame.toString().contains("secret"));
        assertTrue(frame.toString().contains("6 bytes"));
    }
}
