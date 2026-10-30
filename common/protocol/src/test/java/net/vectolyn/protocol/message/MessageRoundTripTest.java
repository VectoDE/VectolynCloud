package net.vectolyn.protocol.message;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Every message survives encode and decode unchanged, and decoding is byte-exact. */
class MessageRoundTripTest {

    private final MessageCodec codec = MessageCodec.standard();

    static Stream<Message> messages() {
        return Stream.concat(SampleMessages.typical().stream(), SampleMessages.atTheLimits().stream());
    }

    @ParameterizedTest
    @MethodSource("messages")
    void decodeReturnsAnEqualMessage(Message message) {
        Envelope envelope = new Envelope(SampleMessages.requestIdFor(message), message);
        assertEquals(envelope, codec.decode(codec.encode(envelope)));
    }

    @ParameterizedTest
    @MethodSource("messages")
    void reencodingTheDecodedMessageGivesTheSameBytes(Message message) {
        byte[] encoded = codec.encode(new Envelope(SampleMessages.requestIdFor(message), message));
        assertArrayEquals(encoded, codec.encode(codec.decode(encoded)));
    }
}
