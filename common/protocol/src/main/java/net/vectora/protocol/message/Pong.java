package net.vectora.protocol.message;

import net.vectora.protocol.PacketReader;
import net.vectora.protocol.PacketWriter;

/**
 * Answer to a {@link Ping}.
 *
 * <p>Wire: nonce (8 bytes).
 *
 * @param nonce the nonce of the ping
 */
public record Pong(long nonce) implements Message {

    /** Returns the answer to {@code ping}. */
    public static Pong answering(Ping ping) {
        return new Pong(ping.nonce());
    }

    /** Reads the payload. */
    public static Pong read(PacketReader in) {
        return new Pong(in.readLong());
    }

    @Override
    public void write(PacketWriter out) {
        out.writeLong(nonce);
    }
}
