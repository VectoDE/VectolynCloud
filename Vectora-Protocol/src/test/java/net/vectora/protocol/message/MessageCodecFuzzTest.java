package net.vectora.protocol.message;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.vectora.protocol.Frame;
import net.vectora.protocol.FrameCodec;
import net.vectora.protocol.Protocol;
import net.vectora.protocol.ProtocolException;

/**
 * Fuzz targets for the message layer. In a normal test run they replay the stored inputs in
 * {@code MessageCodecFuzzTestInputs/<method>/}; the nightly workflow fuzzes them on Linux.
 */
class MessageCodecFuzzTest {

    private static final MessageCodec CODEC = MessageCodec.standard();
    private static final List<MessageType<?>> TYPES = List.copyOf(MessageRegistry.standard().types());

    /** A complete frame from the network: decoding may only fail with {@link ProtocolException}. */
    @FuzzTest
    void decodeFailsOnlyWithProtocolException(byte @NotNull [] input) {
        try {
            CODEC.decode(input);
        } catch (ProtocolException expected) {
            // Malformed input is fine as long as it is reported this way.
        }
    }

    /** Whatever decodes is canonical: encoding it again gives exactly the input bytes. */
    @FuzzTest
    void decodedFramesEncodeToTheSameBytes(byte @NotNull [] input) {
        Frame frame;
        Envelope envelope;
        try {
            frame = FrameCodec.decode(input);
            envelope = CODEC.fromFrame(frame);
        } catch (ProtocolException expected) {
            return;
        }
        assertArrayEquals(input, FrameCodec.encode(frame));
        Frame again = CODEC.toFrame(envelope);
        assertEquals(frame.messageId(), again.messageId());
        assertEquals(frame.requestId(), again.requestId());
        assertArrayEquals(frame.payload(), again.payload());
        assertEquals(envelope, CODEC.fromFrame(again));
    }

    /**
     * Payload of one message type: the first byte picks the type, the rest is the payload. This
     * skips the frame header so the fuzzer spends its time inside the message decoders.
     */
    @FuzzTest
    void payloadOfEveryMessageType(byte @NotNull [] input) {
        if (input.length == 0) {
            return;
        }
        MessageType<?> type = TYPES.get(Math.floorMod(input[0], TYPES.size()));
        byte[] payload = Arrays.copyOfRange(input, 1, input.length);
        Message probe;
        try {
            probe = type.read(new net.vectora.protocol.PacketReader(payload));
        } catch (ProtocolException expected) {
            return;
        }
        int requestId = probe.requiresRequestId() ? 1 : Envelope.NO_REQUEST;
        Frame frame = new Frame(Protocol.PROTOCOL_VERSION, type.id(), requestId, payload);
        Envelope envelope;
        try {
            envelope = CODEC.fromFrame(frame);
        } catch (ProtocolException expected) {
            return; // e.g. bytes left over after the message
        }
        assertArrayEquals(payload, CODEC.toFrame(envelope).payload());
    }

    /** Bytes arriving on a stream: tryDecode either yields frames, waits, or throws ProtocolException. */
    @FuzzTest
    void streamDecodingFailsOnlyWithProtocolException(byte @NotNull [] input) {
        ByteBuffer stream = ByteBuffer.wrap(input);
        try {
            while (true) {
                int before = stream.position();
                Optional<Envelope> next = CODEC.tryDecode(stream);
                if (next.isEmpty()) {
                    assertEquals(before, stream.position(), "an incomplete frame must not be consumed");
                    return;
                }
                assertTrue(stream.position() > before, "a decoded frame must consume bytes");
            }
        } catch (ProtocolException expected) {
            // The connection would be closed here.
        }
    }
}
