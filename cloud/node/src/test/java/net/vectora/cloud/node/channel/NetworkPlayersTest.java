package net.vectora.cloud.node.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.PlayerJoin;
import net.vectora.protocol.message.PlayerQuit;
import net.vectora.protocol.message.PlayerSwitch;
import net.vectora.protocol.message.ServiceReady;
import org.junit.jupiter.api.Test;

class NetworkPlayersTest {

    private static final ServiceId PROXY = new ServiceId("proxy-1");
    private static final ServiceId OTHER_PROXY = new ServiceId("proxy-2");
    private static final ServiceId LOBBY = new ServiceId("lobby-1");
    private static final ServiceId MINIGAME = new ServiceId("minigame-3");
    private static final UUID STEVE = UUID.fromString("00000000-0000-4000-8000-000000000001");

    /** The proxy's names: "lobby" is the static lobby-1, "minigame-3" its own id; lobby-1 itself is no proxy name. */
    private final NetworkPlayers players = new NetworkPlayers((proxy, name) -> {
        if (!proxy.equals(PROXY) && !proxy.equals(OTHER_PROXY)) {
            return Optional.empty();
        }
        return Optional.ofNullable(Map.of("lobby", LOBBY, "minigame-3", MINIGAME).get(name.value()));
    });

    @Test
    void staticNamesAreMappedAndTheSequenceIsKept() {
        assertEquals(NetworkPlayers.Outcome.APPLIED, players.apply(PROXY, join("lobby")));
        assertEquals(LOBBY, players.find(STEVE).orElseThrow().service(), "lobby means lobby-1");
        assertEquals(NetworkPlayers.Outcome.APPLIED, players.apply(PROXY,
                new PlayerSwitch(STEVE, "Steve", new ServiceId("lobby"), MINIGAME, false)));
        assertEquals(List.of(STEVE), players.on(MINIGAME).stream().map(NetworkPlayers.Player::uuid).toList());
        assertTrue(players.on(LOBBY).isEmpty());
        assertEquals(NetworkPlayers.Outcome.APPLIED, players.apply(PROXY, new PlayerQuit(STEVE, "Steve", MINIGAME, false)));
        assertTrue(players.online().isEmpty());
    }

    @Test
    void reportsThatDoNotFitAreDropped() {
        assertEquals(NetworkPlayers.Outcome.UNKNOWN_SERVER, players.apply(LOBBY, join("lobby")), "a backend is no proxy");
        assertEquals(NetworkPlayers.Outcome.UNKNOWN_SERVER, players.apply(PROXY, join("lobby-1")),
                "the proxy calls it lobby, not lobby-1");
        assertEquals(NetworkPlayers.Outcome.UNKNOWN_SERVER, players.apply(PROXY, join("survival")));
        assertEquals(NetworkPlayers.Outcome.INCONSISTENT, players.apply(PROXY, new PlayerQuit(STEVE, "Steve", LOBBY, false)),
                "quit without join");
        assertTrue(players.online().isEmpty());

        players.apply(PROXY, join("lobby"));
        assertEquals(NetworkPlayers.Outcome.INCONSISTENT, players.apply(PROXY,
                new PlayerSwitch(STEVE, "Steve", MINIGAME, new ServiceId("lobby"), false)), "from is not where he is");
        assertEquals(NetworkPlayers.Outcome.INCONSISTENT, players.apply(OTHER_PROXY, join("minigame-3")),
                "online through another proxy");
        assertEquals(NetworkPlayers.Outcome.INCONSISTENT, players.apply(OTHER_PROXY,
                new PlayerQuit(STEVE, "Steve", LOBBY, false)), "another proxy cannot end his session");
        assertEquals(NetworkPlayers.Outcome.NOT_A_PLAYER_EVENT, players.apply(PROXY, new ServiceReady(PROXY)));
        assertEquals(LOBBY, players.find(STEVE).orElseThrow().service(), "nothing of that changed him");

        players.proxyEnded(OTHER_PROXY);
        assertTrue(players.find(STEVE).isPresent(), "counter-check: another proxy's end keeps him");
        players.proxyEnded(PROXY);
        assertTrue(players.find(STEVE).isEmpty(), "his proxy ended");
    }

    private static PlayerJoin join(String server) {
        return new PlayerJoin(STEVE, "Steve", new ServiceId(server), false);
    }
}
