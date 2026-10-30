package net.vectolyn.protocol.message;

import java.util.Objects;
import net.vectolyn.protocol.PacketReader;
import net.vectolyn.protocol.PacketWriter;
import net.vectolyn.protocol.ServiceId;

/**
 * Tells a proxy to remove a backend server from its server list.
 *
 * <p>Wire: name (string, max. 64).
 *
 * @param name service id the server was registered with
 */
public record UnregisterServer(ServiceId name) implements Message {

    /** Creates the message. */
    public UnregisterServer {
        Objects.requireNonNull(name, "name");
    }

    /** Reads the payload. */
    public static UnregisterServer read(PacketReader in) {
        return new UnregisterServer(ServiceId.read(in));
    }

    @Override
    public void write(PacketWriter out) {
        name.write(out);
    }
}
