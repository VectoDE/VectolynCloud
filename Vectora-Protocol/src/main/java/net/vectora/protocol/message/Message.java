package net.vectora.protocol.message;

import net.vectora.protocol.PacketWriter;

/**
 * A message of the cloud channel. Every type is an immutable record, has a static
 * {@code read(PacketReader)} and is listed in {@link MessageRegistry#standard()}.
 */
public sealed interface Message
        permits Hello, AuthResult, Ping, Pong, Disconnect,
                ServiceReady, ServiceStatus, RegisterServer, UnregisterServer,
                PlayerJoin, PlayerQuit, PlayerSwitch,
                ChannelMessage, Request, Response {

    /** Writes the payload of this message. */
    void write(PacketWriter out);

    /**
     * Returns whether this message travels with a request id. {@link Request} and
     * {@link Response} need one (not 0); every other message must use 0.
     */
    default boolean requiresRequestId() {
        return false;
    }
}
