package net.vectolyn.cloud.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.vectolyn.cloud.client.CloudClientSettings;
import org.junit.jupiter.api.Test;

class BridgeConnectionTest {

    private static final String TOKEN = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";

    @Test
    void outsideTheCloudTheBridgeStaysInactive() {
        assertEquals(Optional.empty(), BridgeConnection.settings(key -> null, key -> null));
        Map<String, String> onlyId = Map.of(CloudClientSettings.ENV_SERVICE_ID, "lobby-1");
        assertEquals(Optional.empty(), BridgeConnection.settings(onlyId::get, key -> null));
    }

    @Test
    void readsTheVariablesTheCloudSets() {
        Map<String, String> env = Map.of(CloudClientSettings.ENV_SERVICE_ID, "lobby-1",
                CloudClientSettings.ENV_TOKEN, TOKEN, CloudClientSettings.ENV_PORT, "7011");
        CloudClientSettings settings = BridgeConnection.settings(env::get, key -> null).orElseThrow();
        assertEquals("lobby-1", settings.service().value());
        assertEquals(7011, settings.port());
        assertFalse(settings.toString().contains(TOKEN), "settings never print the token");
    }

    @Test
    void openReturnsAtOnceAndConnectsInTheBackground() throws IOException {
        try (ServerSocket cloud = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Map<String, String> env = Map.of(CloudClientSettings.ENV_SERVICE_ID, "lobby-1",
                    CloudClientSettings.ENV_TOKEN, TOKEN, CloudClientSettings.ENV_HOST, "127.0.0.1",
                    CloudClientSettings.ENV_PORT, Integer.toString(cloud.getLocalPort()));
            CloudClientSettings settings = BridgeConnection.settings(env::get, key -> null).orElseThrow();
            AtomicReference<BridgeSender> seen = new AtomicReference<>();
            long start = System.nanoTime();
            BridgeSender sender = BridgeConnection.open(settings, created -> {
                seen.set(created);
                return event -> {
                    // no messages expected
                };
            });
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000, "open must not block");
            assertSame(sender, seen.get());
            cloud.setSoTimeout(5_000);
            try (Socket accepted = cloud.accept()) {
                assertTrue(accepted.isConnected(), "the client connected in the background");
            }
            sender.close(Duration.ofSeconds(5));
            assertTrue(sender.isClosed());
        }
    }
}
