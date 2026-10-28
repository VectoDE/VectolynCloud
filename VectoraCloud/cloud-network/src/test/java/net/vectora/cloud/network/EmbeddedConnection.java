package net.vectora.cloud.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.vectora.protocol.message.Envelope;
import net.vectora.protocol.message.MessageCodec;

/** The server's pipeline on an in-memory channel, without sockets or threads. */
final class EmbeddedConnection implements AutoCloseable {

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final ByteBuffer written = ByteBuffer.allocate(4 * 1024 * 1024);

    EmbeddedConnection(CloudNetworkServer server) {
        server.initPipeline(channel);
        channel.pipeline().fireChannelActive();
    }

    void receive(byte[] bytes) {
        if (channel.isOpen()) {
            channel.writeInbound(Unpooled.wrappedBuffer(bytes));
        }
    }

    boolean isOpen() {
        return channel.isOpen();
    }

    /** Runs scheduled tasks (timeouts) that are due now. */
    void runPendingTasks() {
        channel.runPendingTasks();
    }

    /** Decodes everything the server wrote so far. Throws ProtocolException if it is not valid. */
    List<Envelope> sent() {
        for (Object out = channel.readOutbound(); out != null; out = channel.readOutbound()) {
            ByteBuf buffer = (ByteBuf) out;
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            buffer.release();
            written.put(bytes);
        }
        List<Envelope> envelopes = new ArrayList<>();
        ByteBuffer view = written.duplicate().flip();
        Optional<Envelope> next;
        while ((next = MessageCodec.standard().tryDecode(view)).isPresent()) {
            envelopes.add(next.get());
        }
        return envelopes;
    }

    @Override
    public void close() {
        channel.finishAndReleaseAll();
    }
}
