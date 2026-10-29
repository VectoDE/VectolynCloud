package net.vectora.protocol.message;

import java.util.Arrays;
import java.util.Objects;
import net.vectora.protocol.ChannelName;
import net.vectora.protocol.PacketReader;
import net.vectora.protocol.PacketWriter;

/**
 * Fire-and-forget message on a named channel, the replacement for BungeeCord plugin channels.
 * The payload format belongs to the channel's owner. The array is copied in and out, and
 * {@code toString()} shows only its length.
 *
 * <p>Wire: channel (string, max. 64), payload (byte array, max. 64 KiB).
 *
 * @param channel channel name
 * @param payload up to {@value MessageLimits#MAX_PAYLOAD_SIZE} bytes
 */
public record ChannelMessage(ChannelName channel, byte[] payload) implements Message {

    /**
     * Creates the message.
     *
     * @throws IllegalArgumentException if the payload exceeds 64 KiB
     */
    public ChannelMessage {
        Objects.requireNonNull(channel, "channel");
        payload = MessageLimits.checkPayload(payload);
    }

    /** Returns a copy of the payload. */
    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /** Reads the payload. */
    public static ChannelMessage read(PacketReader in) {
        ChannelName channel = ChannelName.read(in);
        byte[] payload = in.readByteArray(MessageLimits.MAX_PAYLOAD_SIZE);
        return new ChannelMessage(channel, payload);
    }

    @Override
    public void write(PacketWriter out) {
        channel.write(out);
        out.writeByteArray(payload, MessageLimits.MAX_PAYLOAD_SIZE);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ChannelMessage message
                && channel.equals(message.channel)
                && Arrays.equals(payload, message.payload);
    }

    @Override
    public int hashCode() {
        return 31 * channel.hashCode() + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "ChannelMessage[channel=" + channel + ", payload=" + payload.length + " bytes]";
    }
}
