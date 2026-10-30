package net.vectora.cloud.cluster;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import net.vectora.protocol.ProtocolException;
import net.vectora.protocol.message.Envelope;
import net.vectora.protocol.message.MessageCodec;

/**
 * Frames of the cloud channel over a blocking stream (the TLS socket of the cluster port). The
 * length prefix is checked against the limit as soon as it has arrived, before the body is read.
 * Writes are serialized, so several threads may send.
 */
final class FrameStream {

    private static final MessageCodec CODEC = MessageCodec.standard();
    /** A VarInt has at most 5 bytes. */
    private static final int MAX_PREFIX = 5;

    private final InputStream in;
    private final OutputStream out;
    private final ReentrantLock writeLock = new ReentrantLock();

    FrameStream(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    /**
     * Reads the next envelope.
     *
     * @param maxBody largest frame body accepted
     * @return the envelope
     * @throws EOFException      if the stream ends
     * @throws ProtocolException if the bytes are not a valid frame within the limit
     * @throws IOException       if reading fails (also a socket timeout)
     */
    Envelope read(int maxBody) throws IOException {
        byte[] prefix = new byte[MAX_PREFIX];
        int length = 0;
        int count = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException();
            }
            prefix[count] = (byte) b;
            length |= (b & 0x7f) << (7 * count);
            count++;
            if ((b & 0x80) == 0) {
                break;
            }
            if (count == MAX_PREFIX) {
                throw new ProtocolException("frame length is not a valid VarInt");
            }
        }
        if (length < 0 || length > maxBody) {
            throw new ProtocolException("frame of " + length + " bytes, limit " + maxBody);
        }
        byte[] body = in.readNBytes(length);
        if (body.length < length) {
            throw new EOFException();
        }
        ByteBuffer frame = ByteBuffer.allocate(count + length);
        frame.put(prefix, 0, count).put(body).flip();
        return CODEC.tryDecode(frame, Math.max(maxBody, 4))
                .orElseThrow(() -> new ProtocolException("incomplete frame"));
    }

    /**
     * Writes one envelope and flushes.
     *
     * @throws IOException if writing fails
     */
    void write(Envelope envelope) throws IOException {
        byte[] bytes = CODEC.encode(envelope);
        writeLock.lock();
        try {
            out.write(bytes);
            out.flush();
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Writes one envelope unless another write has been stuck for {@code wait} (a peer that does
     * not read). The caller closes the connection then.
     *
     * @return whether it was written
     * @throws IOException if writing fails
     */
    boolean tryWrite(Envelope envelope, Duration wait) throws IOException {
        byte[] bytes = CODEC.encode(envelope);
        try {
            if (!writeLock.tryLock(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        try {
            out.write(bytes);
            out.flush();
            return true;
        } finally {
            writeLock.unlock();
        }
    }
}
