// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.bridge;

import java.util.concurrent.CompletableFuture;
import net.vectolyn.protocol.message.Message;

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

    /**
     * Sends a request on a channel and returns the cloud's answer. Like every call, it is made on
     * the {@link BridgeSender} thread; the future completes later on another thread. The default
     * fails, for links that cannot ask.
     *
     * @param channel channel name, for example {@code vectolyn.privacy}
     * @param payload request payload
     * @return the answer's payload; fails if the cloud is not connected, does not answer in time or
     *         does not handle the channel
     */
    default CompletableFuture<byte[]> request(String channel, byte[] payload) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("requests are not supported"));
    }

    /** Says goodbye to the cloud and stops the connection. */
    void close();
}
