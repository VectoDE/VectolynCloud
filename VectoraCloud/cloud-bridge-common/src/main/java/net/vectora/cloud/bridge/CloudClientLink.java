package net.vectora.cloud.bridge;

import java.util.Objects;
import net.vectora.cloud.client.CloudClient;
import net.vectora.protocol.message.Message;

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
    public void close() {
        client.close();
    }
}
