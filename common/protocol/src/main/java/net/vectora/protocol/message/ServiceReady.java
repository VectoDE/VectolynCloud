package net.vectora.protocol.message;

import java.util.Objects;
import net.vectora.protocol.PacketReader;
import net.vectora.protocol.PacketWriter;
import net.vectora.protocol.ServiceId;

/**
 * Sent by a service once it has started and accepts players.
 *
 * <p>Wire: service (string, max. 64).
 *
 * @param service the service that is ready
 */
public record ServiceReady(ServiceId service) implements Message {

    /** Creates the message. */
    public ServiceReady {
        Objects.requireNonNull(service, "service");
    }

    /** Reads the payload. */
    public static ServiceReady read(PacketReader in) {
        return new ServiceReady(ServiceId.read(in));
    }

    @Override
    public void write(PacketWriter out) {
        service.write(out);
    }
}
