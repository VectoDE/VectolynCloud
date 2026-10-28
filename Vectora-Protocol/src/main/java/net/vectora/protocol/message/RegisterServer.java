package net.vectora.protocol.message;

import java.util.Objects;
import net.vectora.protocol.GroupName;
import net.vectora.protocol.PacketReader;
import net.vectora.protocol.PacketWriter;
import net.vectora.protocol.ServiceId;

/**
 * Tells a proxy to add a backend server to its server list.
 *
 * <p>Wire: name (string, max. 64), host (string, max. 253), port (unsigned short), group
 * (string, max. 32), legacy (boolean).
 *
 * @param name service id, used as the server name on the proxy
 * @param host host name or IP literal (IPv6 without brackets)
 * @param port TCP port, 1 to 65535
 * @param group group of the server
 * @param legacy {@code true} for 1.8.8 servers behind the legacy proxy
 */
public record RegisterServer(ServiceId name, String host, int port, GroupName group, boolean legacy)
        implements Message {

    /** Largest TCP port. */
    public static final int MAX_PORT = 65_535;

    /**
     * Creates the message.
     *
     * @throws IllegalArgumentException if the host or port is invalid
     */
    public RegisterServer {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(group, "group");
        MessageLimits.checkHost(host);
        if (port < 1 || port > MAX_PORT) {
            throw new IllegalArgumentException("port must be 1 to " + MAX_PORT + ", got " + port);
        }
    }

    /** Reads the payload. */
    public static RegisterServer read(PacketReader in) {
        ServiceId name = ServiceId.read(in);
        String host = in.readString(MessageLimits.MAX_HOST_LENGTH);
        int port = in.readUnsignedShort();
        GroupName group = GroupName.read(in);
        boolean legacy = in.readBoolean();
        return new RegisterServer(name, host, port, group, legacy);
    }

    @Override
    public void write(PacketWriter out) {
        name.write(out);
        out.writeString(host, MessageLimits.MAX_HOST_LENGTH).writeShort(port);
        group.write(out);
        out.writeBoolean(legacy);
    }
}
