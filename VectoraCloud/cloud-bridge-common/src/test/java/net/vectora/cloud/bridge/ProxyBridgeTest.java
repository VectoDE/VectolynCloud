package net.vectora.cloud.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.vectora.cloud.client.CloudClientEvent;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.Disconnect;
import net.vectora.protocol.message.PlayerJoin;
import net.vectora.protocol.message.PlayerQuit;
import net.vectora.protocol.message.UnregisterServer;
import org.junit.jupiter.api.Test;

class ProxyBridgeTest {

    private final FakeLink link = new FakeLink();
    private final BridgeSender sender = BridgeSender.start(link);
    private final ServerRegistryTest.FakeServers servers = new ServerRegistryTest.FakeServers(Set.of("lobby"));
    private final ProxyBridge bridge = new ProxyBridge(sender, servers, ProxyRole.MAIN);

    @Test
    void cloudMessagesChangeTheServerList() {
        bridge.onEvent(new CloudClientEvent.MessageReceived(ServerRegistryTest.server("bedwars-1", 30002, false)));
        bridge.onEvent(new CloudClientEvent.MessageReceived(ServerRegistryTest.server("pvp-1", 30003, true)));
        assertEquals(Set.of("bedwars-1"), bridge.registry().registered(), "legacy server refused on the main proxy");
        bridge.onEvent(new CloudClientEvent.MessageReceived(new UnregisterServer(new ServiceId("bedwars-1"))));
        bridge.onEvent(new CloudClientEvent.Connected());
        bridge.onEvent(new CloudClientEvent.Disconnected(Disconnect.Reason.SHUTDOWN));
        assertTrue(bridge.registry().registered().isEmpty());
        assertEquals(List.of("+bedwars-1@127.0.0.1:30002", "-bedwars-1"), servers.log);
        sender.close(Duration.ofSeconds(5));
    }

    @Test
    void playerEventsAreCheckedAndSentFromTheSenderThread() {
        UUID uuid = UUID.randomUUID();
        Object session = new Object();
        Thread caller = Thread.currentThread();
        bridge.ready();
        bridge.playerDisconnected(new Object(), uuid); // unknown player: nothing
        bridge.playerConnected(session, uuid, "Alex", "lobby");
        bridge.playerConnected(session, uuid, "Alex", "lobby");
        bridge.status(1, 500);
        bridge.playerDisconnected(session, uuid);
        sender.close(Duration.ofSeconds(5));

        assertEquals(List.of("ready", "send", "status", "send", "close"), link.names());
        assertEquals(new PlayerJoin(uuid, "Alex", new ServiceId("lobby"), false), link.calls.get(1).argument());
        assertEquals(StatusSnapshot.proxy(1, 500), link.calls.get(2).argument());
        assertEquals(new PlayerQuit(uuid, "Alex", new ServiceId("lobby"), false), link.calls.get(3).argument());
        link.calls.forEach(call -> assertNotSame(caller, call.thread()));
    }
}
