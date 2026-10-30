package net.vectolyn.protocol.message;

import java.util.Objects;
import java.util.UUID;
import net.vectolyn.protocol.PacketReader;
import net.vectolyn.protocol.PacketWriter;
import net.vectolyn.protocol.ServiceId;

/**
 * A player left the network, sent by the proxy.
 *
 * <p>Wire: UUID (16 bytes), name (string, max. 32), service (string, max. 64), bedrock (boolean).
 *
 * @param uuid player UUID
 * @param name player name: printable ASCII, no space at either end
 * @param service last service of the player
 * @param bedrock {@code true} for Bedrock players
 */
public record PlayerQuit(UUID uuid, String name, ServiceId service, boolean bedrock) implements Message {

    /**
     * Creates the message.
     *
     * @throws IllegalArgumentException if the name is invalid
     */
    public PlayerQuit {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(service, "service");
        MessageLimits.checkPlayerName(name);
    }

    /** Reads the payload. */
    public static PlayerQuit read(PacketReader in) {
        UUID uuid = in.readUuid();
        String name = in.readString(MessageLimits.MAX_PLAYER_NAME_LENGTH);
        ServiceId service = ServiceId.read(in);
        boolean bedrock = in.readBoolean();
        return new PlayerQuit(uuid, name, service, bedrock);
    }

    @Override
    public void write(PacketWriter out) {
        out.writeUuid(uuid).writeString(name, MessageLimits.MAX_PLAYER_NAME_LENGTH);
        service.write(out);
        out.writeBoolean(bedrock);
    }
}
