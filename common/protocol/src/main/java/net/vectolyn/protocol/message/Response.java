package net.vectolyn.protocol.message;

import java.util.Arrays;
import java.util.Objects;
import net.vectolyn.protocol.PacketReader;
import net.vectolyn.protocol.PacketWriter;
import net.vectolyn.protocol.ProtocolException;

/**
 * Answer to a {@link Request}, sent with the request id of that request. The array is copied in
 * and out, and {@code toString()} shows only its length.
 *
 * <p>Wire: status (VarInt), payload (byte array, max. 64 KiB).
 *
 * @param status how the request went
 * @param payload result or error details, up to {@value MessageLimits#MAX_PAYLOAD_SIZE} bytes
 */
public record Response(Status status, byte[] payload) implements Message {

    /**
     * Creates the message.
     *
     * @throws IllegalArgumentException if the payload exceeds 64 KiB
     */
    public Response {
        Objects.requireNonNull(status, "status");
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
    public static Response read(PacketReader in) {
        Status status = Status.fromCode(in.readVarInt());
        byte[] payload = in.readByteArray(MessageLimits.MAX_PAYLOAD_SIZE);
        return new Response(status, payload);
    }

    @Override
    public void write(PacketWriter out) {
        out.writeVarInt(status.code()).writeByteArray(payload, MessageLimits.MAX_PAYLOAD_SIZE);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Response response
                && status == response.status
                && Arrays.equals(payload, response.payload);
    }

    @Override
    public int hashCode() {
        return 31 * status.hashCode() + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "Response[status=" + status + ", payload=" + payload.length + " bytes]";
    }

    /** How a request went. The codes are part of the wire format. */
    public enum Status {
        /** Handled; the payload holds the result. */
        OK(0),
        /** The handler failed; the payload may hold details. */
        ERROR(1),
        /** No handler is registered for the channel. */
        NO_HANDLER(2),
        /** The sender is not allowed to call this channel. */
        DENIED(3);

        private final int code;

        Status(int code) {
            this.code = code;
        }

        /** Returns the wire code. */
        public int code() {
            return code;
        }

        /**
         * Returns the status with this wire code.
         *
         * @throws ProtocolException for an unknown code
         */
        public static Status fromCode(int code) {
            for (Status status : values()) {
                if (status.code == code) {
                    return status;
                }
            }
            throw new ProtocolException("unknown response status " + code);
        }
    }
}
