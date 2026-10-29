package net.vectora.cloud.node.fake;

import java.io.PrintStream;
import java.util.UUID;
import net.vectora.cloud.client.CloudClient;
import net.vectora.cloud.client.CloudClientEvent;
import net.vectora.cloud.client.CloudClientSettings;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.PlayerJoin;
import net.vectora.protocol.message.PlayerQuit;
import net.vectora.protocol.message.PlayerSwitch;
import net.vectora.protocol.message.RegisterServer;
import net.vectora.protocol.message.UnregisterServer;

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
                case CloudClientEvent.MessageReceived received -> {
                    // What a Velocity bridge would apply to its server list.
                    if (received.message() instanceof RegisterServer register) {
                        out.println("[fake] register " + register.name() + " " + register.host() + ":" + register.port()
                                + " group=" + register.group() + " legacy=" + register.legacy());
                    } else if (received.message() instanceof UnregisterServer unregister) {
                        out.println("[fake] unregister " + unregister.name());
                    } else {
                        out.println("[fake] cloud message");
                    }
                }
            }
        });
        client.start();
        client.markReady();
        out.println("[fake] waiting for the cloud");
        return client;
    }

    /**
     * Sends a player event like a proxy bridge, from a console line of the fake server:
     * {@code player join <uuid> <name> <server>}, {@code player switch <uuid> <name> <from> <to>} or
     * {@code player quit <uuid> <name> <server>}. Server names are those of the proxy's list.
     *
     * @param client the client {@link #connect} returned
     * @param out    the server's output
     * @param line   the console line
     */
    public static void command(Object client, PrintStream out, String line) {
        String[] parts = line.split(" ");
        CloudClient cloud = (CloudClient) client;
        UUID uuid = UUID.fromString(parts[2]);
        String name = parts[3];
        boolean sent = switch (parts[1]) {
            case "join" -> cloud.send(new PlayerJoin(uuid, name, new ServiceId(parts[4]), false));
            case "switch" -> cloud.send(new PlayerSwitch(uuid, name, new ServiceId(parts[4]), new ServiceId(parts[5]), false));
            case "quit" -> cloud.send(new PlayerQuit(uuid, name, new ServiceId(parts[4]), false));
            default -> false;
        };
        out.println("[fake] player " + parts[1] + " sent=" + sent);
    }
}
