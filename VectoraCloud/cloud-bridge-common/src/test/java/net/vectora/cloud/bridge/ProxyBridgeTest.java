package net.vectora.cloud.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.vectora.cloud.client.CloudClientEvent;
import net.vectora.protocol.GroupName;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.Disconnect;
import net.vectora.protocol.message.RegisterServer;
import net.vectora.protocol.message.ServerCapacity;
import net.vectora.protocol.message.TransferPlayer;
import net.vectora.protocol.message.PlayerJoin;
import net.vectora.protocol.message.PlayerQuit;
import net.vectora.protocol.message.UnregisterServer;
import org.junit.jupiter.api.Test;

class ProxyBridgeTest {

    private final FakeLink link = new FakeLink();
    private final BridgeSender sender = BridgeSender.start(link);
    private final ServerRegistryTest.FakeServers servers = new ServerRegistryTest.FakeServers(Set.of("lobby"));
    private final FakePlayers players = new FakePlayers();
    private final ProxyBridge bridge = new ProxyBridge(sender, servers, players, ProxyRole.MAIN);

    /** Players on the proxy and the moves the bridge asked for. */
    static final class FakePlayers implements ProxyPlayers {
        final Map<UUID, String> on = new HashMap<>();
        final Map<UUID, Locale> locales = new HashMap<>();
        final List<String> moves = new ArrayList<>();

        @Override
        public Optional<String> currentServer(UUID player) {
            return Optional.ofNullable(on.get(player));
        }

        @Override
        public Locale locale(UUID player) {
            return locales.get(player);
        }

        @Override
        public void move(UUID player, String server, String message) {
            moves.add(server + ": " + message);
        }
    }

    private void receive(net.vectora.protocol.message.Message message) {
        bridge.onEvent(new CloudClientEvent.MessageReceived(message));
    }

    private void lobbies() {
        receive(new RegisterServer(new ServiceId("lobby"), "127.0.0.1", 30001, new GroupName("lobby"), false));
        receive(ServerRegistryTest.server("lobby-2", 30002, false));
        receive(ServerRegistryTest.server("bedwars-1", 30003, false));
        receive(new ServerCapacity(new ServiceId("lobby"), 50, true));
        receive(new ServerCapacity(new ServiceId("lobby-2"), 50, true));
        receive(new ServerCapacity(new ServiceId("bedwars-1"), 8, true));
    }

    @Test
    void joinsAndRedirectsGoToTheEmptiestServerOfTheGroup() {
        lobbies();
        servers.players.put("lobby", 30);
        servers.players.put("lobby-2", 5);
        assertEquals(Optional.of("lobby-2"), bridge.initialServer(UUID.randomUUID(), "lobby"));
        assertEquals(Optional.of("lobby"), bridge.redirect(UUID.randomUUID(), "lobby-2", "lobby"),
                "never back to the server that kicked the player");
        assertEquals(Optional.empty(), bridge.initialServer(UUID.randomUUID(), "survival"), "unknown group: proxy's choice");
        receive(new UnregisterServer(new ServiceId("lobby-2")));
        assertEquals(Optional.of("lobby"), bridge.initialServer(UUID.randomUUID(), "lobby"));
        sender.close(Duration.ofSeconds(5));
    }

    @Test
    void transfersAreOnlyCarriedOutWithinTheGroupToAnOpenListedServer() {
        lobbies();
        UUID alex = UUID.randomUUID();
        players.on.put(alex, "lobby-2");
        players.locales.put(alex, Locale.GERMANY);
        TransferPlayer.Reason why = TransferPlayer.Reason.SCALE_DOWN;
        receive(new TransferPlayer(alex, new ServiceId("bedwars-1"), why));   // other group
        receive(new TransferPlayer(alex, new ServiceId("lobby-2"), why));     // where the player is
        receive(new TransferPlayer(alex, new ServiceId("lobby-7"), why));     // unknown
        receive(new TransferPlayer(UUID.randomUUID(), new ServiceId("lobby"), why)); // not online
        receive(new ServerCapacity(new ServiceId("lobby"), 50, false));
        receive(new TransferPlayer(alex, new ServiceId("lobby"), why));       // closed
        assertTrue(players.moves.isEmpty(), players.moves.toString());
        // Counter-check: the fitting transfer is carried out, with the German line.
        receive(new ServerCapacity(new ServiceId("lobby"), 50, true));
        receive(new TransferPlayer(alex, new ServiceId("lobby"), why));
        assertEquals(List.of("lobby: " + TransferMessages.text(why, Locale.GERMANY)), players.moves);
        sender.close(Duration.ofSeconds(5));
    }

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
