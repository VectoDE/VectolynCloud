package net.vectora.cloud.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import net.vectora.protocol.Protocol;
import net.vectora.protocol.ProtocolException;
import net.vectora.protocol.message.AuthResult;
import net.vectora.protocol.message.Envelope;
import net.vectora.protocol.message.MessageCodec;

/**
 * Fuzz target for what the client reads from the cloud. Stored inputs live in
 * {@code FrameReaderFuzzTestInputs/<method>/}; the nightly workflow fuzzes it on Linux.
 */
class FrameReaderFuzzTest {

    /**
     * Any bytes: the reader returns messages, or ends with EOFException or ProtocolException,
     * and never reads a frame above the limit that applies at that moment.
     */
    @FuzzTest
    void readerFailsOnlyWithEofOrProtocolException(byte @NotNull [] input) throws IOException {
        FrameReader reader = new FrameReader(new ByteArrayInputStream(input), MessageCodec.standard());
        try {
            boolean authenticated = false;
            while (true) {
                Envelope envelope = reader.read();
                int size = MessageCodec.standard().toFrame(envelope).bodySize();
                assertTrue(size <= (authenticated ? Protocol.MAX_FRAME_SIZE : Protocol.MAX_HANDSHAKE_FRAME_SIZE));
                if (envelope.message() instanceof AuthResult result && result.outcome() == AuthResult.Outcome.ACCEPTED) {
                    reader.authenticated();
                    authenticated = true;
                }
            }
        } catch (EOFException | ProtocolException expected) {
            // End of input, or bytes the client would answer with Disconnect(PROTOCOL_ERROR).
        }
    }
}
