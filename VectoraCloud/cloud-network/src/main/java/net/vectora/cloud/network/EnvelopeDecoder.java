package net.vectora.cloud.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import net.vectora.protocol.Protocol;
import net.vectora.protocol.ProtocolException;
import net.vectora.protocol.message.Envelope;
import net.vectora.protocol.message.MessageCodec;

/**
 * Turns the byte stream into {@link Envelope}s with the codec of {@code Vectora-Protocol}.
 *
 * <p>Until {@link #authenticated()} is called, a frame body may have at most
 * {@link Protocol#MAX_HANDSHAKE_FRAME_SIZE} bytes; afterwards {@link Protocol#MAX_FRAME_SIZE}. The
 * length prefix is checked as soon as it is complete, so a peer cannot make the server buffer
 * more than the limit. After the first {@link ProtocolException} the stream cannot be trusted any
 * more: everything that follows is dropped while the handler closes the connection.
 */
final class EnvelopeDecoder extends ByteToMessageDecoder {

    private final MessageCodec codec;
    private int maxBodySize = Protocol.MAX_HANDSHAKE_FRAME_SIZE;
    private boolean failed;

    EnvelopeDecoder(MessageCodec codec) {
        this.codec = codec;
    }

    /** Raises the frame limit to the full size once the peer has authenticated. */
    void authenticated() {
        maxBodySize = Protocol.MAX_FRAME_SIZE;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (failed) {
            in.skipBytes(in.readableBytes());
            return;
        }
        // A view of the readable bytes, no copy: the codec reads the message straight from it.
        ByteBuffer view = in.nioBuffer(in.readerIndex(), in.readableBytes());
        Optional<Envelope> next;
        try {
            next = codec.tryDecode(view, maxBodySize);
        } catch (ProtocolException e) {
            failed = true;
            in.skipBytes(in.readableBytes());
            throw e;
        }
        if (next.isPresent()) {
            in.skipBytes(view.position());
            out.add(next.get());
        }
    }
}
