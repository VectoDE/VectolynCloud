package net.vectora.protocol.message;

import java.util.Arrays;
import java.util.Objects;
import net.vectora.protocol.ChannelName;
import net.vectora.protocol.PacketReader;
import net.vectora.protocol.PacketWriter;

/**
 * A call that expects a {@link Response}. The frame carries a request id other than 0, chosen
 * by the sender and unique among its open requests; the response comes back with the same id.
 * The handler is found by channel name. The array is copied in and out, and {@code toString()}
 * shows only its length.
 *
 * <p>Wire: channel (string, max. 64), payload (byte array, max. 64 KiB).
 *
 * @param channel channel that handles the request
 * @param payload up to {@value MessageLimits#MAX_PAYLOAD_SIZE} bytes
 */
public record Request(ChannelName channel, byte[] payload) implements Message {

    /**
     * Creates the message.
     *
     * @throws IllegalArgumentException if the payload exceeds 64 KiB
     */
    public Request {
        Objects.requireNonNull(channel, "channel");
        payload = MessageLimits.checkPayload(payload);
    }

    /** Returns a copy of the payload. */
    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean requiresRequestId() {
        return true;
    }

    /** Reads the payload. */
    public static Request read(PacketReader in) {
        ChannelName channel = ChannelName.read(in);
        byte[] payload = in.readByteArray(MessageLimits.MAX_PAYLOAD_SIZE);
        return new Request(channel, payload);
    }

    @Override
    public void write(PacketWriter out) {
        channel.write(out);
        out.writeByteArray(payload, MessageLimits.MAX_PAYLOAD_SIZE);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Request request
                && channel.equals(request.channel)
                && Arrays.equals(payload, request.payload);
    }

    @Override
    public int hashCode() {
        return 31 * channel.hashCode() + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "Request[channel=" + channel + ", payload=" + payload.length + " bytes]";
    }
}
