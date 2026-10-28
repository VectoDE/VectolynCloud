package net.vectora.protocol.message;

import java.util.Objects;
import net.vectora.protocol.PacketReader;
import net.vectora.protocol.PacketWriter;
import net.vectora.protocol.ProtocolException;

/**
 * Last message before a side closes the connection.
 *
 * <p>Wire: reason (VarInt).
 *
 * @param reason why the connection ends
 */
public record Disconnect(Reason reason) implements Message {

    /** Creates the message. */
    public Disconnect {
        Objects.requireNonNull(reason, "reason");
    }

    /** Reads the payload. */
    public static Disconnect read(PacketReader in) {
        return new Disconnect(Reason.fromCode(in.readVarInt()));
    }

    @Override
    public void write(PacketWriter out) {
        out.writeVarInt(reason.code());
    }

    /** Why a connection ends. The codes are part of the wire format. */
    public enum Reason {
        /** Planned close without an error. */
        NORMAL(0),
        /** The sender shuts down. */
        SHUTDOWN(1),
        /** Authentication failed. */
        AUTH_FAILED(2),
        /** The major protocol versions differ. */
        INCOMPATIBLE_VERSION(3),
        /** The sender received bytes it could not decode. */
        PROTOCOL_ERROR(4),
        /** No ping or other message arrived in time. */
        TIMEOUT(5),
        /** A newer connection with the same id replaced this one. */
        REPLACED(6);

        private final int code;

        Reason(int code) {
            this.code = code;
        }

        /** Returns the wire code. */
        public int code() {
            return code;
        }

        /**
         * Returns the reason with this wire code.
         *
         * @throws ProtocolException for an unknown code
         */
        public static Reason fromCode(int code) {
            for (Reason reason : values()) {
                if (reason.code == code) {
                    return reason;
                }
            }
            throw new ProtocolException("unknown disconnect reason " + code);
        }
    }
}
