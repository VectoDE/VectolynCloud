package net.vectora.protocol.message;

import java.util.Objects;
import net.vectora.protocol.AuthToken;
import net.vectora.protocol.PacketReader;
import net.vectora.protocol.PacketWriter;
import net.vectora.protocol.PeerId;
import net.vectora.protocol.ProtocolVersion;

/**
 * First message of every connection, sent by the connecting peer. The cloud answers with
 * {@link AuthResult}. {@code toString()} does not show the token.
 *
 * <p>Wire: version (2 bytes), role (1 byte), id (string, max. 64), token (string, max. 256).
 *
 * @param version protocol version the peer speaks
 * @param peer who connects: a service or a cluster node
 * @param token secret that proves it
 */
public record Hello(ProtocolVersion version, PeerId peer, AuthToken token) implements Message {

    /** Creates the message. */
    public Hello {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(token, "token");
    }

    /** Reads the payload. */
    public static Hello read(PacketReader in) {
        ProtocolVersion version = ProtocolVersion.read(in);
        PeerId peer = PeerId.readWithRole(in);
        AuthToken token = AuthToken.read(in);
        return new Hello(version, peer, token);
    }

    @Override
    public void write(PacketWriter out) {
        version.write(out);
        peer.writeWithRole(out);
        token.write(out);
    }
}
