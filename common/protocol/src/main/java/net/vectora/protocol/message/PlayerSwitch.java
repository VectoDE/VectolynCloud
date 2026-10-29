package net.vectora.protocol.message;

import java.util.Objects;
import java.util.UUID;
import net.vectora.protocol.PacketReader;
import net.vectora.protocol.PacketWriter;
import net.vectora.protocol.ServiceId;

/**
 * A player moved from one service to another, sent by the proxy.
 *
 * <p>Wire: UUID (16 bytes), name (string, max. 32), from and to (string, max. 64 each),
 * bedrock (boolean).
 *
 * @param uuid player UUID
 * @param name player name: printable ASCII, no space at either end
 * @param from service the player left
 * @param to service the player is on now, different from {@code from}
 * @param bedrock {@code true} for Bedrock players
 */
public record PlayerSwitch(UUID uuid, String name, ServiceId from, ServiceId to, boolean bedrock)
        implements Message {

    /**
     * Creates the message.
     *
     * @throws IllegalArgumentException if the name is invalid or both services are the same
     */
    public PlayerSwitch {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        MessageLimits.checkPlayerName(name);
        if (from.equals(to)) {
            throw new IllegalArgumentException("a switch needs two different services");
        }
    }

    /** Reads the payload. */
    public static PlayerSwitch read(PacketReader in) {
        UUID uuid = in.readUuid();
        String name = in.readString(MessageLimits.MAX_PLAYER_NAME_LENGTH);
        ServiceId from = ServiceId.read(in);
        ServiceId to = ServiceId.read(in);
        boolean bedrock = in.readBoolean();
        return new PlayerSwitch(uuid, name, from, to, bedrock);
    }

    @Override
    public void write(PacketWriter out) {
        out.writeUuid(uuid).writeString(name, MessageLimits.MAX_PLAYER_NAME_LENGTH);
        from.write(out);
        to.write(out);
        out.writeBoolean(bedrock);
    }
}
