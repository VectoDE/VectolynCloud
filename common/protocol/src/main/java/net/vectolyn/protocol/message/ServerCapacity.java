package net.vectolyn.protocol.message;

import java.util.Objects;
import net.vectolyn.protocol.PacketReader;
import net.vectolyn.protocol.PacketWriter;
import net.vectolyn.protocol.ServiceId;

/**
 * Tells a proxy how many players a backend of its server list is meant for and whether new players
 * may be sent there. The proxy uses it to send a player who enters a group to the backend with the
 * lowest load in percent (players on it divided by {@code slots}). Since protocol 1.1.
 *
 * <p>Wire: name (string, max. 64), slots (VarInt, 1 to 1,000,000), open (boolean).
 *
 * @param name  the server name on the proxy, as in {@code RegisterServer}
 * @param slots players the backend is meant for ({@code players-per-service} of its task)
 * @param open  {@code false} while the cloud empties the backend before it stops: the proxy sends
 *              no new player there
 */
public record ServerCapacity(ServiceId name, int slots, boolean open) implements Message {

    /**
     * Creates the message.
     *
     * @throws IllegalArgumentException if {@code slots} is outside 1 to {@value MessageLimits#MAX_PLAYERS}
     */
    public ServerCapacity {
        Objects.requireNonNull(name, "name");
        if (slots < 1 || slots > MessageLimits.MAX_PLAYERS) {
            throw new IllegalArgumentException("slots must be 1 to " + MessageLimits.MAX_PLAYERS + ", got " + slots);
        }
    }

    /** Reads the payload. */
    public static ServerCapacity read(PacketReader in) {
        ServiceId name = ServiceId.read(in);
        int slots = in.readVarInt();
        boolean open = in.readBoolean();
        return new ServerCapacity(name, slots, open);
    }

    @Override
    public void write(PacketWriter out) {
        name.write(out);
        out.writeVarInt(slots).writeBoolean(open);
    }
}
