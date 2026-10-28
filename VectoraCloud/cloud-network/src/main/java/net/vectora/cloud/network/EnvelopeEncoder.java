package net.vectora.cloud.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import net.vectora.protocol.message.Envelope;
import net.vectora.protocol.message.MessageCodec;

/**
 * Writes {@link Envelope}s as frames. A {@link ByteBuf} that is already an encoded frame (a
 * channel message fanned out to many services is encoded once) passes through unchanged.
 */
@Sharable
final class EnvelopeEncoder extends MessageToByteEncoder<Envelope> {

    private final MessageCodec codec;

    EnvelopeEncoder(MessageCodec codec) {
        super(Envelope.class);
        this.codec = codec;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Envelope envelope, ByteBuf out) {
        out.writeBytes(codec.encode(envelope));
    }
}
