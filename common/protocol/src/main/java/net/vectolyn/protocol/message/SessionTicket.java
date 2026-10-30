package net.vectolyn.protocol.message;

import java.util.Objects;
import net.vectolyn.protocol.AuthToken;
import net.vectolyn.protocol.PacketReader;
import net.vectolyn.protocol.PacketWriter;

/**
 * The secret for the next {@code Hello} of this service, sent by the cloud right after
 * {@code AuthResult(ACCEPTED)}. Since protocol 1.2.
 *
 * <p>A service token is valid for one accepted connection only: the token the service started
 * with (in its environment) is used up by the first connection, and every accepted connection
 * hands out a new ticket that only this connection has seen. A reconnect presents the newest
 * ticket. A token copied from the environment of the process, or a ticket seen once, is
 * therefore refused. {@code toString()} does not show the ticket.
 *
 * <p>Wire: ticket (string, max. 256).
 *
 * @param ticket the token for the next {@code Hello}
 */
public record SessionTicket(AuthToken ticket) implements Message {

    /** Creates the message. */
    public SessionTicket {
        Objects.requireNonNull(ticket, "ticket");
    }

    /** Reads the payload. */
    public static SessionTicket read(PacketReader in) {
        return new SessionTicket(AuthToken.read(in));
    }

    @Override
    public void write(PacketWriter out) {
        ticket.write(out);
    }
}
