package net.vectora.cloud.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.vectora.cloud.client.CloudClientEvent;
import net.vectora.protocol.GroupName;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.Disconnect;
import net.vectora.protocol.message.NetworkKick;
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
    private final ProxyBridge bridge = new ProxyBridge(sender, servers, players, ProxyRole.MAIN,
            ProxyBridge.DEFAULT_FALLBACK_GROUP);

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

        final List<String> disconnects = new ArrayList<>();

        @Override
        public boolean disconnect(UUID player, String reason) {
            if (!on.containsKey(player)) {
                return false;
            }
            disconnects.add(reason);
            on.remove(player);
            return true;
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
        assertEquals(KickOutcome.move("lobby", ""), bridge.redirect(UUID.randomUUID(), "lobby-2", "lobby"),
                "never back to the server that kicked the player");
        assertEquals(Optional.empty(), bridge.initialServer(UUID.randomUUID(), "survival"), "unknown group: proxy's choice");
        receive(new UnregisterServer(new ServiceId("lobby-2")));
        assertEquals(Optional.of("lobby"), bridge.initialServer(UUID.randomUUID(), "lobby"));
        sender.close(Duration.ofSeconds(5));
    }

    @Test
    void redirectsOfTheProxyGoThroughTheKickGuardSoTheyCannotPingPong() {
        lobbies();
        receive(ServerRegistryTest.server("lobby-3", 30005, false));
        receive(new ServerCapacity(new ServiceId("lobby-3"), 50, true));
        servers.players.put("lobby", 30);
        servers.players.put("lobby-2", 5);
        servers.players.put("lobby-3", 40);
        UUID alex = UUID.randomUUID();
        assertEquals(KickOutcome.move("lobby-2", ""), bridge.redirect(alex, "lobby", "lobby"));
        assertEquals(KickOutcome.move("lobby-3", ""), bridge.redirect(alex, "lobby-2", "lobby"),
                "lobby was tried in this incident: not back there although it is emptier");
        assertEquals(KickOutcome.disconnect(TransferMessages.lost(null)), bridge.redirect(alex, "lobby-3", "lobby"),
                "every lobby tried and Velocity's target among them: no ping-pong, disconnect");
        // Counter-check: another player is not affected, and leaving the proxy ends the incident.
        assertEquals(KickOutcome.move("lobby-2", ""), bridge.redirect(UUID.randomUUID(), "lobby", "lobby"));
        bridge.playerDisconnected(new Object(), alex);
        assertEquals(KickOutcome.move("lobby-2", ""), bridge.redirect(alex, "lobby", "lobby"));
        sender.close(Duration.ofSeconds(5));
    }

    @Test
    void redirectsAndCaughtKicksShareTheLimit() {
        lobbies();
        bedwars2();
        UUID alex = UUID.randomUUID();
        // Velocity's target outside the cloud's groups: kept, but counted.
        assertEquals(KickOutcome.keep(), bridge.redirect(alex, "bedwars-1", "survival"));
        assertEquals(move("bedwars-2", null), bridge.kicked(alex, "bedwars-1", SHUTDOWN, false));
        assertEquals(KickOutcome.move("lobby", ""), bridge.redirect(alex, "bedwars-2", "lobby-2"));
        assertEquals(KickOutcome.disconnect(TransferMessages.lost(null)), bridge.redirect(alex, "lobby", "lobby-2"),
                "the fourth move within 60 s");
        assertEquals(KickOutcome.disconnect(TransferMessages.lost(null)), bridge.kicked(alex, "lobby", SHUTDOWN, false),
                "also for a caught kick");
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

    private void bedwars2() {
        receive(ServerRegistryTest.server("bedwars-2", 30004, false));
        receive(new ServerCapacity(new ServiceId("bedwars-2"), 8, true));
    }

    private static final KickReason SHUTDOWN = KickReason.of(List.of("multiplayer.disconnect.server_shutdown"), "");
    private static final KickReason FULL = KickReason.of(List.of("multiplayer.disconnect.server_full"), "");
    private static final KickReason BANNED = KickReason.of(List.of("multiplayer.disconnect.banned"), "");

    private static KickOutcome move(String server, Locale locale) {
        return KickOutcome.move(server, TransferMessages.rescued(locale));
    }

    @Test
    void aKickedPlayerGoesToTheGroupThenToTheLobbyAndEachServerOnlyOnce() {
        lobbies();
        bedwars2();
        servers.players.put("lobby", 30);
        servers.players.put("lobby-2", 5);
        UUID alex = UUID.randomUUID();
        players.locales.put(alex, Locale.GERMANY);
        assertEquals(move("bedwars-2", Locale.GERMANY), bridge.kicked(alex, "bedwars-1", KickReason.missing(), false));
        assertEquals(move("lobby-2", Locale.GERMANY), bridge.kicked(alex, "bedwars-2", SHUTDOWN, false),
                "bedwars-1 was already tried: the emptiest lobby");
        assertEquals(move("lobby", Locale.GERMANY), bridge.kicked(alex, "lobby-2", KickReason.missing(), false),
                "the other lobby, not lobby-2 again");
        assertEquals(KickOutcome.disconnect(TransferMessages.lost(Locale.GERMANY)),
                bridge.kicked(alex, "lobby", KickReason.missing(), false), "the fourth rescue within 60 s");
        // Counter-check: after leaving the proxy the player is rescued again.
        bridge.playerDisconnected(new Object(), alex);
        assertEquals(move("lobby-2", Locale.GERMANY), bridge.kicked(alex, "lobby", KickReason.missing(), false));
        sender.close(Duration.ofSeconds(5));
    }

    @Test
    void aRollingRestartSendsThePlayerBackOnceHeReachedTheOtherServer() {
        lobbies();
        UUID alex = UUID.randomUUID();
        UUID steve = UUID.randomUUID();
        // lobby restarts: both go to lobby-2; only Alex gets there before lobby-2 restarts too.
        assertEquals(move("lobby-2", null), bridge.kicked(alex, "lobby", SHUTDOWN, false));
        assertEquals(move("lobby-2", null), bridge.kicked(steve, "lobby", SHUTDOWN, false));
        bridge.playerConnected(new Object(), alex, "Alex", "lobby-2");
        assertEquals(move("lobby", null), bridge.kicked(alex, "lobby-2", SHUTDOWN, false),
                "lobby is back and free for him again");
        // Counter-check: without the arrival lobby stays excluded and Steve has nowhere to go.
        assertEquals(KickOutcome.disconnect(TransferMessages.lost(null)),
                bridge.kicked(steve, "lobby-2", SHUTDOWN, false));
        sender.close(Duration.ofSeconds(5));
    }

    @Test
    void aKickThatArrivesAfterTheServerWasWithdrawnStaysInItsGroup() {
        lobbies();
        bedwars2();
        // The cloud withdraws the stopping server before the proxy reports the kick of its players.
        receive(new UnregisterServer(new ServiceId("bedwars-1")));
        assertEquals(move("bedwars-2", null), bridge.kicked(UUID.randomUUID(), "bedwars-1", SHUTDOWN, false),
                "the group of bedwars-1 is still known: not the lobby");
        // Counter-check: a server the cloud never named has no group; the player goes to the lobby.
        assertEquals(move("lobby", null), bridge.kicked(UUID.randomUUID(), "bedwars-9", SHUTDOWN, false));
        sender.close(Duration.ofSeconds(5));
    }

    @Test
    void bansWhitelistVanillaKicksAndMarkedKicksAreLeftAsTheProxyDecided() {
        lobbies();
        bedwars2();
        UUID alex = UUID.randomUUID();
        assertEquals(KickOutcome.keep(), bridge.kicked(alex, "bedwars-1", BANNED, false));
        assertEquals(KickOutcome.keep(), bridge.kicked(alex, "bedwars-1",
                KickReason.of(List.of("multiplayer.disconnect.not_whitelisted"), ""), false));
        assertEquals(KickOutcome.keep(), bridge.kicked(alex, "bedwars-1",
                KickReason.of(List.of("multiplayer.disconnect.kicked"), ""), false));
        assertEquals(KickOutcome.keep(), bridge.kicked(alex, "bedwars-1",
                KickReason.of(List.of(), "Kicked by an operator."), false));
        assertEquals(KickOutcome.keep(), bridge.kicked(alex, "bedwars-1",
                KickReason.of(List.of(KickClassifier.MODERATION_KEY + ".kick"), "Please behave"), false));
        // Counter-check: the same kick from a shutdown is caught.
        assertEquals(move("bedwars-2", null), bridge.kicked(alex, "bedwars-1", SHUTDOWN, false));
        sender.close(Duration.ofSeconds(5));
    }

    private static final KickReason SPAM = KickReason.of(List.of(), "Spam");

    private NetworkKick networkKick(UUID player, String server, String reason) {
        return new NetworkKick(player, new ServiceId(server), reason);
    }

    @Test
    void aNetworkKickBeforeTheKickDisconnectsAtOnceAndTheKickIsNotCaught() {
        lobbies();
        bedwars2();
        UUID alex = UUID.randomUUID();
        players.on.put(alex, "bedwars-1");
        receive(networkKick(alex, "bedwars-1", "Spam"));
        assertEquals(List.of("Spam"), players.disconnects, "off the network with the backend's reason");
        // The backend's kick arrives afterwards: a punishment, not caught.
        assertEquals(KickOutcome.disconnect("Spam"), bridge.kicked(alex, "bedwars-1", SPAM, false));
        assertTrue(bridge.kickedOrMarked(alex, "bedwars-1", SPAM, false).isDone(), "no wait with a mark");
        assertTrue(players.moves.isEmpty(), players.moves.toString());
        sender.close(Duration.ofSeconds(5));
    }

    @Test
    void aNetworkKickShortlyAfterTheKickEndsTheWaitWithADisconnect() throws Exception {
        lobbies();
        bedwars2();
        UUID alex = UUID.randomUUID();
        CompletableFuture<KickOutcome> outcome = bridge.kickedOrMarked(alex, "bedwars-1", SPAM, false);
        assertFalse(outcome.isDone(), "a kick with own words waits for a mark");
        // The player is between the servers; the mark comes over the cloud a moment later.
        receive(networkKick(alex, "bedwars-1", "Spam"));
        assertEquals(KickOutcome.disconnect("Spam"), outcome.get(5, TimeUnit.SECONDS));
        assertTrue(players.disconnects.isEmpty(), "the waiting kick disconnects, not a second time");
        sender.close(Duration.ofSeconds(5));
    }

    @Test
    void aNetworkKickAfterTheWaitStillTakesTheMovedPlayerOffTheNetwork() throws Exception {
        lobbies();
        bedwars2();
        UUID alex = UUID.randomUUID();
        players.locales.put(alex, Locale.GERMANY);
        assertEquals(KickOutcome.moveShowingReason("bedwars-2", TransferMessages.rescued(Locale.GERMANY)),
                bridge.kickedOrMarked(alex, "bedwars-1", SPAM, false).get(5, TimeUnit.SECONDS),
                "no mark within the wait: caught like a plugin's kick");
        players.on.put(alex, "bedwars-2");
        bridge.playerConnected(new Object(), alex, "Alex", "bedwars-2");
        // A slow cloud: the mark comes after the move.
        receive(networkKick(alex, "bedwars-1", ""));
        assertEquals(List.of(TransferMessages.kicked(Locale.GERMANY)), players.disconnects,
                "empty reason (vanilla text): the bridge's own line");
        sender.close(Duration.ofSeconds(5));
    }

    @Test
    void aPluginKickWithoutANetworkKickIsCaught() throws Exception {
        lobbies();
        bedwars2();
        UUID alex = UUID.randomUUID();
        assertEquals(KickOutcome.moveShowingReason("bedwars-2", TransferMessages.rescued(null)),
                bridge.kickedOrMarked(alex, "bedwars-1", SPAM, false).get(5, TimeUnit.SECONDS));
        assertTrue(bridge.kickedOrMarked(UUID.randomUUID(), "bedwars-1", SHUTDOWN, false).isDone(),
                "a stop does not wait");
        assertTrue(bridge.kickedOrMarked(UUID.randomUUID(), "bedwars-1", BANNED, false).isDone(),
                "a ban does not wait");
        assertTrue(players.disconnects.isEmpty());
        sender.close(Duration.ofSeconds(5));
    }

    @Test
    void aNetworkKickIsIgnoredForAnUnlistedServerOrAPlayerWhoIsNotOnIt() {
        lobbies();
        bedwars2();
        UUID alex = UUID.randomUUID();
        players.on.put(alex, "bedwars-2");
        receive(networkKick(alex, "bedwars-9", "Spam"));   // not listed by the cloud
        receive(networkKick(alex, "bedwars-1", "Spam"));   // the player is on bedwars-2 and was not kicked
        receive(networkKick(UUID.randomUUID(), "bedwars-2", "Spam")); // not online here
        assertTrue(players.disconnects.isEmpty(), players.disconnects.toString());
        assertEquals(move("bedwars-1", null), bridge.kicked(alex, "bedwars-2", SHUTDOWN, false),
                "an ignored mark does not turn the next kick into a punishment");
        // Counter-check: from the server the player is on, the mark takes effect.
        players.on.put(alex, "bedwars-2");
        receive(networkKick(alex, "bedwars-2", "Spam"));
        assertEquals(List.of("Spam"), players.disconnects);
        sender.close(Duration.ofSeconds(5));
    }

    @Test
    void aKickWithUnknownWordsIsCaughtAndTheReasonShown() {
        lobbies();
        bedwars2();
        UUID alex = UUID.randomUUID();
        players.locales.put(alex, Locale.GERMANY);
        KickOutcome outcome = bridge.kicked(alex, "bedwars-1", KickReason.of(List.of(), "Please behave"), false);
        assertEquals(KickOutcome.moveShowingReason("bedwars-2", TransferMessages.rescued(Locale.GERMANY)), outcome);
        assertTrue(outcome.showReason(), "the player reads the original reason after the move");
        assertEquals(KickOutcome.moveShowingReason("lobby", TransferMessages.rescued(Locale.GERMANY)),
                bridge.kicked(alex, "bedwars-2", KickReason.of(List.of("multiplayer.disconnect.idling"), ""), false),
                "a vanilla idle kick as well; no other bedwars server: the fallback group");
        // Counter-check: a stop shows the bridge's notice, not the reason.
        assertFalse(bridge.kicked(UUID.randomUUID(), "bedwars-1", SHUTDOWN, false).showReason());
        sender.close(Duration.ofSeconds(5));
    }

    @Test
    void withoutAnyOpenServerThePlayerIsDisconnected() {
        lobbies();
        receive(new ServerCapacity(new ServiceId("lobby"), 50, false));
        receive(new ServerCapacity(new ServiceId("lobby-2"), 50, false));
        UUID alex = UUID.randomUUID();
        assertEquals(KickOutcome.disconnect(TransferMessages.lost(null)),
                bridge.kicked(alex, "bedwars-1", SHUTDOWN, false));
        // Counter-check: with an open lobby the player goes there, also from a server of no known group.
        receive(new ServerCapacity(new ServiceId("lobby-2"), 50, true));
        assertEquals(move("lobby-2", null), bridge.kicked(UUID.randomUUID(), "bedwars-1", SHUTDOWN, false));
        assertEquals(move("lobby-2", null), bridge.kicked(UUID.randomUUID(), "survival", SHUTDOWN, false));
        sender.close(Duration.ofSeconds(5));
    }

    @Test
    void theFallbackGroupComesFromTheCloud() {
        assertEquals("lobby", ProxyBridge.DEFAULT_FALLBACK_GROUP);
        assertEquals("hub", ProxyBridge.fallbackGroup(Map.of("VECTORA_FALLBACK_GROUP", "hub")::get));
        assertEquals("hub", ProxyBridge.fallbackGroup(Map.of("VECTORA_FALLBACK_GROUP", " hub ")::get));
        // Counter-checks: missing, blank and invalid values give the default.
        assertEquals("lobby", ProxyBridge.fallbackGroup(Map.<String, String>of()::get));
        assertEquals("lobby", ProxyBridge.fallbackGroup(Map.of("VECTORA_FALLBACK_GROUP", " ")::get));
        assertEquals("lobby", ProxyBridge.fallbackGroup(Map.of("VECTORA_FALLBACK_GROUP", "Hub!")::get));
        assertEquals("lobby", ProxyBridge.fallbackGroup(Map.of("VECTORA_FALLBACK_GROUP", "con")::get));
        sender.close(Duration.ofSeconds(5));
    }

    @Test
    void aKickedPlayerGoesToTheConfiguredFallbackGroup() {
        ServerRegistryTest.FakeServers hubServers = new ServerRegistryTest.FakeServers(Set.of());
        ProxyBridge hubBridge = new ProxyBridge(sender, hubServers, players, ProxyRole.MAIN, "hub");
        hubBridge.onEvent(new CloudClientEvent.MessageReceived(ServerRegistryTest.server("hub-1", 30006, false)));
        hubBridge.onEvent(new CloudClientEvent.MessageReceived(ServerRegistryTest.server("lobby-2", 30002, false)));
        hubBridge.onEvent(new CloudClientEvent.MessageReceived(ServerRegistryTest.server("bedwars-1", 30003, false)));
        hubBridge.onEvent(new CloudClientEvent.MessageReceived(new ServerCapacity(new ServiceId("hub-1"), 50, true)));
        hubBridge.onEvent(new CloudClientEvent.MessageReceived(new ServerCapacity(new ServiceId("lobby-2"), 50, true)));
        hubBridge.onEvent(new CloudClientEvent.MessageReceived(new ServerCapacity(new ServiceId("bedwars-1"), 8, true)));
        hubServers.players.put("lobby-2", 0);
        hubServers.players.put("hub-1", 40);
        assertEquals(move("hub-1", null), hubBridge.kicked(UUID.randomUUID(), "bedwars-1", SHUTDOWN, false),
                "the hub, although lobby-2 is emptier");
        // Counter-check: the default bridge sends the same player to the lobby.
        lobbies();
        assertEquals(move("lobby", null), bridge.kicked(UUID.randomUUID(), "bedwars-1", SHUTDOWN, false));
        sender.close(Duration.ofSeconds(5));
    }

    @Test
    void aPlayerWhoStaysOnHisServerIsOnlyMovedInsideTheGroup() {
        lobbies();
        bedwars2();
        UUID alex = UUID.randomUUID();
        assertEquals(move("bedwars-2", null), bridge.kicked(alex, "bedwars-1", FULL, true),
                "a full server during the connect: another one of the group");
        assertEquals(KickOutcome.keep(), bridge.kicked(alex, "bedwars-2", FULL, true),
                "no other bedwars server: the player stays where he is, no lobby");
        // Counter-check: without a server to stay on, the lobby takes him.
        assertEquals(move("lobby", null), bridge.kicked(alex, "bedwars-2", FULL, false));
        sender.close(Duration.ofSeconds(5));
    }

    @Test
    void aServerTheCloudClosedTakesNobodyWithoutTheBypass() {
        lobbies();
        assertEquals("vectora.server.bypass-closed", ProxyBridge.BYPASS_CLOSED_PERMISSION);
        assertTrue(bridge.mayConnect("lobby-2", false, true), "open");
        receive(new ServerCapacity(new ServiceId("lobby-2"), 50, false));
        assertFalse(bridge.mayConnect("lobby-2", false, true), "closed");
        // Counter-checks: the bypass, the first server of a login, a server the cloud never closed.
        assertTrue(bridge.mayConnect("lobby-2", true, true), "bypass");
        assertTrue(bridge.mayConnect("lobby-2", false, false), "first server of a login");
        assertTrue(bridge.mayConnect("survival", false, true), "unknown to the cloud");
        receive(ServerRegistryTest.server("bedwars-2", 30004, false));
        assertTrue(bridge.mayConnect("bedwars-2", false, true), "no slots known yet");
        receive(new ServerCapacity(new ServiceId("lobby-2"), 50, true));
        assertTrue(bridge.mayConnect("lobby-2", false, true), "open again");
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
