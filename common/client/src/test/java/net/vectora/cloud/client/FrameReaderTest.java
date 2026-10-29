package net.vectora.cloud.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import net.vectora.protocol.ChannelName;
import net.vectora.protocol.Protocol;
import net.vectora.protocol.ProtocolException;
import net.vectora.protocol.VarInts;
import net.vectora.protocol.message.AuthResult;
import net.vectora.protocol.message.ChannelMessage;
import net.vectora.protocol.message.Envelope;
import net.vectora.protocol.message.MessageCodec;
import net.vectora.protocol.message.Ping;
import org.junit.jupiter.api.Test;

class FrameReaderTest {

    private static final MessageCodec CODEC = MessageCodec.standard();

    @Test
    void readsFramesThatArriveOneByteAtATime() throws IOException {
        Envelope auth = Envelope.of(new AuthResult(AuthResult.Outcome.ACCEPTED, Protocol.PROTOCOL_VERSION));
        Envelope big = Envelope.of(new ChannelMessage(new ChannelName("c"), new byte[20_000]));
        Envelope ping = Envelope.of(new Ping(3));
        FrameReader reader = new FrameReader(trickle(concat(CODEC.encode(auth), CODEC.encode(big), CODEC.encode(ping))),
                CODEC);
        assertEquals(auth, reader.read());
        reader.authenticated();
        assertEquals(big, reader.read());
        assertEquals(ping, reader.read());
        assertThrows(EOFException.class, reader::read);
    }

    @Test
    void beforeAuthenticationOnlySmallFramesAreRead() {
        Envelope big = Envelope.of(new ChannelMessage(new ChannelName("c"), new byte[Protocol.MAX_HANDSHAKE_FRAME_SIZE]));
        FrameReader reader = new FrameReader(new ByteArrayInputStream(CODEC.encode(big)), CODEC);
        assertThrows(ProtocolException.class, reader::read);
    }

    @Test
    void anOversizedPrefixIsRefusedWithoutWaitingForTheBody() {
        ByteBuffer prefix = ByteBuffer.allocate(VarInts.MAX_BYTES);
        VarInts.write(prefix, Protocol.MAX_FRAME_SIZE + 1);
        FrameReader reader = new FrameReader(new ByteArrayInputStream(Arrays.copyOf(prefix.array(), prefix.position())),
                CODEC);
        reader.authenticated();
        assertThrows(ProtocolException.class, reader::read);
    }

    @Test
    void streamEndingInsideAFrameIsAnEof() {
        byte[] frame = CODEC.encode(Envelope.of(new Ping(1)));
        FrameReader reader = new FrameReader(new ByteArrayInputStream(Arrays.copyOf(frame, frame.length - 1)), CODEC);
        assertThrows(EOFException.class, reader::read);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** A stream that hands out one byte per read, like a slow network. */
    private static InputStream trickle(byte[] bytes) {
        return new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 1));
            }
        };
    }
}
