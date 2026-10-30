package net.vectolyn.protocol.message;

import net.vectolyn.protocol.PacketReader;
import net.vectolyn.protocol.PacketWriter;

/**
 * Keep-alive and latency check; the other side answers with a {@link Pong} carrying the same
 * nonce. Either side may send it.
 *
 * <p>Wire: nonce (8 bytes).
 *
 * @param nonce any value, echoed back
 */
public record Ping(long nonce) implements Message {

    /** Reads the payload. */
    public static Ping read(PacketReader in) {
        return new Ping(in.readLong());
    }

    @Override
    public void write(PacketWriter out) {
        out.writeLong(nonce);
    }
}
