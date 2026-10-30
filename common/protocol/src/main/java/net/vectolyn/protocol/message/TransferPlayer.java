package net.vectolyn.protocol.message;

import java.util.Objects;
import java.util.UUID;
import net.vectolyn.protocol.PacketReader;
import net.vectolyn.protocol.PacketWriter;
import net.vectolyn.protocol.ProtocolException;
import net.vectolyn.protocol.ServiceId;

/**
 * Asks a proxy to move one of its players to another backend, sent by the cloud. The proxy tells
 * the player why, in the player's language, and connects them; the {@code PlayerSwitch} that
 * follows tells the cloud that it worked. A proxy only carries it out for a player on a backend of
 * the same group as {@code to}. Since protocol 1.1. Only the cloud sends it and only a proxy acts
 * on it; the cloud closes the connection of a peer that sends one.
 *
 * <p>Wire: UUID (16 bytes), to (string, max. 64), reason (VarInt).
 *
 * @param uuid   the player
 * @param to     the server name on the proxy to move the player to
 * @param reason why, for the message the player sees
 */
public record TransferPlayer(UUID uuid, ServiceId to, Reason reason) implements Message {

    /** Creates the message. */
    public TransferPlayer {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(reason, "reason");
    }

    /** Reads the payload. */
    public static TransferPlayer read(PacketReader in) {
        UUID uuid = in.readUuid();
        ServiceId to = ServiceId.read(in);
        Reason reason = Reason.fromCode(in.readVarInt());
        return new TransferPlayer(uuid, to, reason);
    }

    @Override
    public void write(PacketWriter out) {
        out.writeUuid(uuid);
        to.write(out);
        out.writeVarInt(reason.code());
    }

    /** Why a player is moved. The codes are part of the wire format. */
    public enum Reason {
        /** The backend stops because its group has more room than it needs. */
        SCALE_DOWN(0);

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
            throw new ProtocolException("unknown transfer reason " + code);
        }
    }
}
