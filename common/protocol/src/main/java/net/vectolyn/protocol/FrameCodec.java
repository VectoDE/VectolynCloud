package net.vectolyn.protocol;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Turns {@link Frame}s into bytes and back.
 *
 * <p>Layout of one frame on the stream:
 * <pre>
 * VarInt  length      size of everything after this field, {@value #MIN_BODY_SIZE} to 1 MiB
 * u8      major       protocol major version
 * u8      minor       protocol minor version
 * VarInt  messageId   message type, 0 or more
 * VarInt  requestId   pairs request and response, 0 = none
 * byte[]  payload     the rest of the body
 * </pre>
 *
 * <p>Decoding is strict: a length outside the limits, a negative id, a non-minimal VarInt or
 * missing bytes throw {@link ProtocolException}. The length is checked as soon as it arrives,
 * before any body bytes are buffered.
 */
public final class FrameCodec {

    /** Smallest valid body: two version bytes and two one-byte VarInts. */
    public static final int MIN_BODY_SIZE = 4;

    private FrameCodec() {
    }

    /** Encodes {@code frame} with its length prefix. */
    public static byte[] encode(Frame frame) {
        int bodySize = frame.bodySize();
        ByteBuffer out = ByteBuffer.allocate(VarInts.size(bodySize) + bodySize);
        VarInts.write(out, bodySize);
        out.put((byte) frame.version().major());
        out.put((byte) frame.version().minor());
        VarInts.write(out, frame.messageId());
        VarInts.write(out, frame.requestId());
        out.put(frame.payload());
        return out.array();
    }

    /**
     * Decodes exactly one frame that fills {@code bytes} completely.
     *
     * @throws ProtocolException if the bytes are not one valid frame, are cut off or have bytes
     *     left over
     */
    public static Frame decode(byte[] bytes) {
        ByteBuffer in = ByteBuffer.wrap(bytes);
        Frame frame = tryDecode(in).orElseThrow(() -> new ProtocolException("frame is incomplete"));
        if (in.hasRemaining()) {
            throw new ProtocolException(in.remaining() + " bytes after the frame");
        }
        return frame;
    }

    /**
     * Decodes the next frame of a stream with the full limit of {@link Protocol#MAX_FRAME_SIZE}.
     * If {@code in} does not yet hold a complete frame, the position stays where it was and the
     * result is empty; call again when more bytes have arrived. On success the position moves
     * past the frame.
     *
     * @throws ProtocolException if the bytes can never become a valid frame; the connection
     *     should then be closed, the buffer position is unspecified
     */
    public static Optional<Frame> tryDecode(ByteBuffer in) {
        return tryDecode(in, Protocol.MAX_FRAME_SIZE);
    }

    /**
     * Like {@link #tryDecode(ByteBuffer)}, but with a smaller limit for the frame body, for
     * example {@link Protocol#MAX_HANDSHAKE_FRAME_SIZE} before authentication.
     *
     * @param maxBodySize largest accepted body, {@value #MIN_BODY_SIZE} to
     *     {@link Protocol#MAX_FRAME_SIZE}
     * @throws IllegalArgumentException if {@code maxBodySize} is outside that range
     * @throws ProtocolException if the bytes can never become a valid frame
     */
    public static Optional<Frame> tryDecode(ByteBuffer in, int maxBodySize) {
        return tryReadBody(in, maxBodySize).map(FrameCodec::decodeBody);
    }

    /**
     * Finds the next frame of a stream and returns its body (header and payload) without copying
     * it. The returned buffer is a read-only view of {@code in} and only valid until the bytes of
     * {@code in} are overwritten, so decode it right away. Read the header with
     * {@link FrameHeader#read}.
     *
     * <p>The length prefix is checked against {@code maxBodySize} as soon as it is complete, before
     * any body byte is waited for. If the frame is not complete yet, the position of {@code in}
     * stays where it was and the result is empty; otherwise the position moves past the frame.
     *
     * @param maxBodySize largest accepted body, {@value #MIN_BODY_SIZE} to
     *     {@link Protocol#MAX_FRAME_SIZE}
     * @throws IllegalArgumentException if {@code maxBodySize} is outside that range
     * @throws ProtocolException if the length prefix is invalid or outside the limits
     */
    public static Optional<ByteBuffer> tryReadBody(ByteBuffer in, int maxBodySize) {
        if (maxBodySize < MIN_BODY_SIZE || maxBodySize > Protocol.MAX_FRAME_SIZE) {
            throw new IllegalArgumentException("frame limit must be " + MIN_BODY_SIZE + " to "
                    + Protocol.MAX_FRAME_SIZE + ", got " + maxBodySize);
        }
        int start = in.position();
        if (!holdsCompleteVarInt(in)) {
            return Optional.empty();
        }
        int length = VarInts.read(in);
        if (length < MIN_BODY_SIZE || length > maxBodySize) {
            throw new ProtocolException("frame length " + length + " is outside " + MIN_BODY_SIZE + " to "
                    + maxBodySize);
        }
        if (in.remaining() < length) {
            in.position(start);
            return Optional.empty();
        }
        ByteBuffer body = in.slice(in.position(), length).asReadOnlyBuffer();
        in.position(in.position() + length);
        return Optional.of(body);
    }

    private static Frame decodeBody(ByteBuffer body) {
        PacketReader reader = new PacketReader(body);
        FrameHeader header = FrameHeader.read(reader);
        return new Frame(header.version(), header.messageId(), header.requestId(), reader.readRemainingBytes());
    }

    /**
     * Returns whether a VarInt can be read at the position: its last byte has arrived, or five
     * bytes are there, in which case {@link VarInts#read} decides whether it is valid.
     */
    private static boolean holdsCompleteVarInt(ByteBuffer in) {
        int available = Math.min(in.remaining(), VarInts.MAX_BYTES);
        if (available == VarInts.MAX_BYTES) {
            return true;
        }
        for (int i = 0; i < available; i++) {
            if ((in.get(in.position() + i) & 0x80) == 0) {
                return true;
            }
        }
        return false;
    }
}
