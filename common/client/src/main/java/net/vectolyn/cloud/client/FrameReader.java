package net.vectolyn.cloud.client;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Optional;
import net.vectolyn.protocol.Protocol;
import net.vectolyn.protocol.ProtocolException;
import net.vectolyn.protocol.VarInts;
import net.vectolyn.protocol.message.Envelope;
import net.vectolyn.protocol.message.MessageCodec;

/**
 * Reads envelopes from a blocking stream. The buffer starts small and grows only up to the frame
 * limit: {@link Protocol#MAX_HANDSHAKE_FRAME_SIZE} until {@link #authenticated()}, then
 * {@link Protocol#MAX_FRAME_SIZE}. Not thread-safe; one reader thread per connection.
 */
final class FrameReader {

    private static final int INITIAL_SIZE = 8 * 1024;

    private final InputStream in;
    private final MessageCodec codec;
    private byte[] buffer = new byte[INITIAL_SIZE];
    private int start;
    private int end;
    private int maxBodySize = Protocol.MAX_HANDSHAKE_FRAME_SIZE;

    FrameReader(InputStream in, MessageCodec codec) {
        this.in = in;
        this.codec = codec;
    }

    /** Raises the frame limit to the full size once the cloud has accepted the client. */
    void authenticated() {
        maxBodySize = Protocol.MAX_FRAME_SIZE;
    }

    /**
     * Blocks until the next message has arrived.
     *
     * @throws EOFException if the stream ends, also in the middle of a frame
     * @throws ProtocolException if the bytes are not a valid message
     * @throws IOException if reading fails, for example on a read timeout
     */
    Envelope read() throws IOException {
        while (true) {
            ByteBuffer view = ByteBuffer.wrap(buffer, start, end - start);
            Optional<Envelope> next = codec.tryDecode(view, maxBodySize);
            if (next.isPresent()) {
                start = view.position();
                return next.get();
            }
            makeRoom();
            int read = in.read(buffer, end, buffer.length - end);
            if (read < 0) {
                throw new EOFException("connection closed by the cloud");
            }
            end += read;
        }
    }

    /** Moves unread bytes to the front and grows the buffer if it is full, up to the frame limit. */
    private void makeRoom() {
        if (start > 0) {
            System.arraycopy(buffer, start, buffer, 0, end - start);
            end -= start;
            start = 0;
        }
        if (end == buffer.length) {
            // A complete length prefix above the limit has been refused already, so a full buffer
            // smaller than the largest frame can always grow.
            int largest = maxBodySize + VarInts.MAX_BYTES;
            if (buffer.length >= largest) {
                throw new ProtocolException("frame does not fit the limit of " + maxBodySize + " bytes");
            }
            byte[] grown = new byte[Math.min(Math.max(buffer.length * 2, INITIAL_SIZE), largest)];
            System.arraycopy(buffer, 0, grown, 0, end);
            buffer = grown;
        }
    }
}
