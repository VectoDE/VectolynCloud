package net.vectora.cloud.node.fake;

import java.io.PrintStream;
import net.vectora.cloud.client.CloudClient;
import net.vectora.cloud.client.CloudClientEvent;
import net.vectora.cloud.client.CloudClientSettings;

/**
 * The cloud side of {@link FakeServer} in {@code fake.cloud} mode: connects with the id and token
 * from the environment, like a platform bridge, and reports readiness. Prints every connection
 * event, so the tests can see a rejected token.
 */
public final class FakeCloudBridge {

    private FakeCloudBridge() {
    }

    /**
     * Connects to the cloud and marks the service ready.
     *
     * @param out the server's output
     * @return the client, closed by the server when it stops
     */
    public static AutoCloseable connect(PrintStream out) {
        CloudClient client = CloudClient.create(CloudClientSettings.fromEnvironment());
        client.addListener(event -> {
            switch (event) {
                case CloudClientEvent.Connected connected -> out.println("[fake] cloud connected");
                case CloudClientEvent.Rejected rejected -> out.println("[fake] cloud rejected " + rejected.outcome());
                case CloudClientEvent.Disconnected disconnected ->
                        out.println("[fake] cloud disconnected " + disconnected.reason());
                case CloudClientEvent.MessageReceived received -> out.println("[fake] cloud message");
            }
        });
        client.start();
        client.markReady();
        out.println("[fake] waiting for the cloud");
        return client;
    }
}
