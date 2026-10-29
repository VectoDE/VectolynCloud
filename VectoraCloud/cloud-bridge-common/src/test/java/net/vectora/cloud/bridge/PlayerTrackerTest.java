package net.vectora.cloud.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.Message;
import net.vectora.protocol.message.PlayerJoin;
import net.vectora.protocol.message.PlayerQuit;
import net.vectora.protocol.message.PlayerSwitch;
import org.junit.jupiter.api.Test;

class PlayerTrackerTest {

    private static final UUID JAVA = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final UUID BEDROCK = new UUID(0, 0x0009_0000_1234_5678L);

    private final Set<String> servers = Set.of("lobby", "lobby-2", "bedwars-1", "Not_A_Service");
    private final PlayerTracker tracker = new PlayerTracker(servers::contains);
    private final Object session = new Object();

    @Test
    void joinSwitchQuitIsReportedOnceEach() {
        assertEquals(List.of(new PlayerJoin(JAVA, "Notch", id("lobby"), false)),
                tracker.connected(session, JAVA, "Notch", "lobby"));
        assertEquals(List.of(new PlayerSwitch(JAVA, "Notch", id("lobby"), id("bedwars-1"), false)),
                tracker.connected(session, JAVA, "Notch", "bedwars-1"));
        assertEquals(Optional.of(new PlayerQuit(JAVA, "Notch", id("bedwars-1"), false)),
                tracker.disconnected(session, JAVA));
        assertEquals(0, tracker.size());
    }

    @Test
    void repeatedOrUnknownEventsProduceNothing() {
        assertEquals(Optional.empty(), tracker.disconnected(new Object(), JAVA), "quit without join");
        tracker.connected(session, JAVA, "Notch", "lobby");
        assertEquals(List.of(), tracker.connected(session, JAVA, "Notch", "lobby"), "same server again");
        tracker.disconnected(session, JAVA);
        assertEquals(Optional.empty(), tracker.disconnected(session, JAVA), "second quit");
    }

    @Test
    void aSwitchIsReportedFromTheLastReportedServiceWhateverThePlatformSays() {
        tracker.connected(session, JAVA, "Notch", "lobby");
        // A lost platform event: the proxy thinks the player was on lobby-2, the cloud was told lobby.
        List<Message> change = tracker.connected(session, JAVA, "Notch", "bedwars-1");
        assertEquals(List.of(new PlayerSwitch(JAVA, "Notch", id("lobby"), id("bedwars-1"), false)), change);
    }

    @Test
    void theNameFromTheJoinIsKept() {
        tracker.connected(session, JAVA, "Notch", "lobby");
        PlayerSwitch change = (PlayerSwitch) tracker.connected(session, JAVA, "Forged", "lobby-2").getFirst();
        assertEquals("Notch", change.name());
        assertEquals("Notch", ((PlayerQuit) tracker.disconnected(session, JAVA).orElseThrow()).name());
    }

    @Test
    void unknownServersAndInvalidServiceIdsAreIgnored() {
        assertEquals(List.of(), tracker.connected(session, JAVA, "Notch", "elsewhere-1"), "not on the proxy");
        assertEquals(List.of(), tracker.connected(session, JAVA, "Notch", "Not_A_Service"), "not a service id");
        assertEquals(List.of(), tracker.connected(session, JAVA, "Notch", null));
        assertEquals(0, tracker.size());

        tracker.connected(session, JAVA, "Notch", "lobby");
        assertEquals(List.of(), tracker.connected(session, JAVA, "Notch", "elsewhere-1"));
        assertEquals(Optional.of(id("lobby")), tracker.serviceOf(JAVA), "state unchanged");
    }

    @Test
    void invalidPlayerNamesAreNotTracked() {
        assertEquals(List.of(), tracker.connected(session, JAVA, " Notch", "lobby"));
        assertEquals(List.of(), tracker.connected(session, JAVA, "", "lobby"));
        assertEquals(List.of(), tracker.connected(session, JAVA, "Notäch", "lobby"));
        assertEquals(List.of(), tracker.connected(session, JAVA, null, "lobby"));
        assertEquals(Optional.empty(), tracker.disconnected(session, JAVA));
        // Counter-check: a valid name is tracked (in a session that has not ended).
        assertEquals(1, tracker.connected(new Object(), JAVA, "Notch", "lobby").size());
    }

    @Test
    void aConnectAfterTheDisconnectOfItsSessionIsIgnored() {
        tracker.connected(session, JAVA, "Notch", "lobby");
        assertTrue(tracker.disconnected(session, JAVA).isPresent());
        assertEquals(List.of(), tracker.connected(session, JAVA, "Notch", "bedwars-1"), "late connect");
        assertEquals(0, tracker.size());

        // Also when the disconnect overtook the very first connect of the session.
        Object early = new Object();
        assertEquals(Optional.empty(), tracker.disconnected(early, JAVA));
        assertEquals(List.of(), tracker.connected(early, JAVA, "Notch", "lobby"));
        assertEquals(0, tracker.size());
    }

    @Test
    void anOldSessionsLateDisconnectLeavesTheNewSessionAlone() {
        Object old = new Object();
        Object current = new Object();
        tracker.connected(old, JAVA, "Notch", "lobby");
        // The new login connects before the old session's disconnect arrives.
        assertEquals(List.of(new PlayerQuit(JAVA, "Notch", id("lobby"), false),
                new PlayerJoin(JAVA, "Notch", id("bedwars-1"), false)),
                tracker.connected(current, JAVA, "Notch", "bedwars-1"));
        assertEquals(Optional.empty(), tracker.disconnected(old, JAVA), "old session's disconnect");
        assertEquals(Optional.of(id("bedwars-1")), tracker.serviceOf(JAVA));
        assertEquals(Optional.of(new PlayerQuit(JAVA, "Notch", id("bedwars-1"), false)),
                tracker.disconnected(current, JAVA));
    }

    @Test
    void floodgateUuidsAreBedrockPlayers() {
        PlayerJoin join = (PlayerJoin) tracker.connected(session, BEDROCK, ".Steve", "lobby").getFirst();
        assertTrue(join.bedrock());
        assertTrue(((PlayerQuit) tracker.disconnected(session, BEDROCK).orElseThrow()).bedrock());
        assertFalse(PlayerTracker.isBedrock(JAVA));
    }

    private static ServiceId id(String value) {
        return new ServiceId(value);
    }
}
