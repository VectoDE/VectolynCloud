package net.vectora.protocol.message;

import java.util.Objects;
import java.util.UUID;
import net.vectora.protocol.PacketReader;
import net.vectora.protocol.PacketWriter;
import net.vectora.protocol.ServiceId;

/**
 * A kick that is a punishment and must take the player off the whole network: a {@code /kick} of
 * an entitled sender or a kick of the moderation. Since protocol 1.1.
 *
 * <ul>
 *   <li>Backend → cloud: {@code server} is the backend's own service id; the cloud closes the
 *       connection of a service that names another one.</li>
 *   <li>Cloud → proxy: the cloud passes it on only if the sender is a backend it has registered,
 *       with {@code server} set to the backend's name on the proxy, to every running proxy of the
 *       backend's kind. The proxy disconnects the player if they are on it.</li>
 * </ul>
 * A proxy never sends it; the cloud drops one from a service that is not a backend.
 *
 * <p>Wire: UUID (16 bytes), server (string, max. 64), reason (string, max. {@value #MAX_REASON_LENGTH}
 * characters).
 *
 * @param uuid   the player
 * @param server the backend that kicks
 * @param reason the plain text of the kick reason, without colour codes; empty for the vanilla
 *               text, the proxy then shows its own line in the player's language
 */
public record NetworkKick(UUID uuid, ServiceId server, String reason) implements Message {

    /** Longest reason in characters. */
    public static final int MAX_REASON_LENGTH = 256;

    /**
     * UTF-8 needs at most three bytes per Java character (a surrogate pair, two characters, takes
     * four), so every reason of full length fits.
     */
    private static final int MAX_REASON_BYTES = MAX_REASON_LENGTH * 3;

    /**
     * Creates the message.
     *
     * @throws IllegalArgumentException if the reason is longer than {@value #MAX_REASON_LENGTH}
     *     characters
     */
    public NetworkKick {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(reason, "reason");
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("reason must be at most " + MAX_REASON_LENGTH + " characters long");
        }
    }

    /** Reads the payload. */
    public static NetworkKick read(PacketReader in) {
        UUID uuid = in.readUuid();
        ServiceId server = ServiceId.read(in);
        String reason = in.readString(MAX_REASON_BYTES);
        return new NetworkKick(uuid, server, reason);
    }

    @Override
    public void write(PacketWriter out) {
        out.writeUuid(uuid);
        server.write(out);
        out.writeString(reason, MAX_REASON_BYTES);
    }
}
