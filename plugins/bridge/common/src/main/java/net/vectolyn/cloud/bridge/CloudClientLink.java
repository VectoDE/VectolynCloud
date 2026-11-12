// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.bridge;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.vectolyn.cloud.client.CloudClient;
import net.vectolyn.protocol.ChannelName;
import net.vectolyn.protocol.message.Message;
import net.vectolyn.protocol.message.Response;

/** {@link CloudLink} on top of a {@link CloudClient}. */
public final class CloudClientLink implements CloudLink {

    private final CloudClient client;

    /** Wraps {@code client}. */
    public CloudClientLink(CloudClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public void start() {
        client.start();
    }

    @Override
    public void markReady() {
        client.markReady();
    }

    @Override
    public void reportStatus(StatusSnapshot status) {
        client.reportStatus(status.playersOnline(), status.maxPlayers(), status.tps(), status.mspt());
    }

    @Override
    public void send(Message playerEvent) {
        client.send(playerEvent);
    }

    @Override
    public CompletableFuture<byte[]> request(String channel, byte[] payload) {
        return client.request(new ChannelName(channel), payload).thenApply(response -> {
            if (response.status() != Response.Status.OK) {
                throw new IllegalStateException("the cloud answered " + response.status());
            }
            return response.payload();
        });
    }

    @Override
    public void close() {
        client.close();
    }
}
