package net.vectora.protocol.message;

import java.util.Objects;
import net.vectora.protocol.PacketReader;
import net.vectora.protocol.PacketWriter;
import net.vectora.protocol.ProtocolException;
import net.vectora.protocol.ProtocolVersion;

/**
 * The cloud's answer to {@link Hello}. On anything but {@link Outcome#ACCEPTED} the cloud closes
 * the connection after sending it.
 *
 * <p>Wire: outcome (VarInt), cloud version (2 bytes).
 *
 * @param outcome result of the check
 * @param version protocol version of the cloud, so a rejected peer can report it
 */
public record AuthResult(Outcome outcome, ProtocolVersion version) implements Message {

    /** Creates the message. */
    public AuthResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(version, "version");
    }

    /** Reads the payload. */
    public static AuthResult read(PacketReader in) {
        Outcome outcome = Outcome.fromCode(in.readVarInt());
        ProtocolVersion version = ProtocolVersion.read(in);
        return new AuthResult(outcome, version);
    }

    @Override
    public void write(PacketWriter out) {
        out.writeVarInt(outcome.code());
        version.write(out);
    }

    /** Result of the authentication. The codes are part of the wire format. */
    public enum Outcome {
        /** Token and id are valid; the connection is open. */
        ACCEPTED(0),
        /** The token does not match. */
        INVALID_TOKEN(1),
        /** The cloud does not know this service or node. */
        UNKNOWN_PEER(2),
        /** The major protocol versions differ. */
        INCOMPATIBLE_VERSION(3),
        /** Another connection with the same id is already open. */
        ALREADY_CONNECTED(4);

        private final int code;

        Outcome(int code) {
            this.code = code;
        }

        /** Returns the wire code. */
        public int code() {
            return code;
        }

        /**
         * Returns the outcome with this wire code.
         *
         * @throws ProtocolException for an unknown code
         */
        public static Outcome fromCode(int code) {
            for (Outcome outcome : values()) {
                if (outcome.code == code) {
                    return outcome;
                }
            }
            throw new ProtocolException("unknown auth outcome " + code);
        }
    }
}
