package net.vectora.cloud.bridge;

import net.vectora.protocol.message.Message;

/**
 * The calls a bridge makes on the cloud channel. {@link CloudClientLink} passes them to
 * {@code CloudClient}; tests use a fake. Every method may block on the socket, so the bridges call
 * them only from the {@link BridgeSender} thread.
 */
public interface CloudLink {

    /** Starts connecting in the background. */
    void start();

    /** Tells the cloud that the service accepts players. */
    void markReady();

    /** Sends a status report. */
    void reportStatus(StatusSnapshot status);

    /** Sends a player event ({@code PlayerJoin}, {@code PlayerQuit}, {@code PlayerSwitch}). */
    void send(Message playerEvent);

    /** Says goodbye to the cloud and stops the connection. */
    void close();
}
