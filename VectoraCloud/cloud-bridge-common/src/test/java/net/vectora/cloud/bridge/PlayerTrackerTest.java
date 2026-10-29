package net.vectora.cloud.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void joinSwitchQuitIsReportedOnceEach() {
        assertEquals(Optional.of(new PlayerJoin(JAVA, "Notch", id("lobby"), false)),
                tracker.connected(JAVA, "Notch", "lobby"));
        assertEquals(Optional.of(new PlayerSwitch(JAVA, "Notch", id("lobby"), id("bedwars-1"), false)),
                tracker.connected(JAVA, "Notch", "bedwars-1"));
        assertEquals(Optional.of(new PlayerQuit(JAVA, "Notch", id("bedwars-1"), false)),
                tracker.disconnected(JAVA));
        assertEquals(0, tracker.size());
    }

    @Test
    void repeatedOrUnknownEventsProduceNothing() {
        assertEquals(Optional.empty(), tracker.disconnected(JAVA), "quit without join");
        tracker.connected(JAVA, "Notch", "lobby");
        assertEquals(Optional.empty(), tracker.connected(JAVA, "Notch", "lobby"), "same server again");
        tracker.disconnected(JAVA);
        assertEquals(Optional.empty(), tracker.disconnected(JAVA), "second quit");
    }

    @Test
    void aSwitchIsReportedFromTheLastReportedServiceWhateverThePlatformSays() {
        tracker.connected(JAVA, "Notch", "lobby");
        // A lost platform event: the proxy thinks the player was on lobby-2, the cloud was told lobby.
        Optional<Message> change = tracker.connected(JAVA, "Notch", "bedwars-1");
        assertEquals(Optional.of(new PlayerSwitch(JAVA, "Notch", id("lobby"), id("bedwars-1"), false)), change);
    }

    @Test
    void theNameFromTheJoinIsKept() {
        tracker.connected(JAVA, "Notch", "lobby");
        PlayerSwitch change = (PlayerSwitch) tracker.connected(JAVA, "Forged", "lobby-2").orElseThrow();
        assertEquals("Notch", change.name());
        assertEquals("Notch", ((PlayerQuit) tracker.disconnected(JAVA).orElseThrow()).name());
    }

    @Test
    void unknownServersAndInvalidServiceIdsAreIgnored() {
        assertEquals(Optional.empty(), tracker.connected(JAVA, "Notch", "elsewhere-1"), "not on the proxy");
        assertEquals(Optional.empty(), tracker.connected(JAVA, "Notch", "Not_A_Service"), "not a service id");
        assertEquals(Optional.empty(), tracker.connected(JAVA, "Notch", null));
        assertEquals(0, tracker.size());

        tracker.connected(JAVA, "Notch", "lobby");
        assertEquals(Optional.empty(), tracker.connected(JAVA, "Notch", "elsewhere-1"));
        assertEquals(Optional.of(id("lobby")), tracker.serviceOf(JAVA), "state unchanged");
    }

    @Test
    void invalidPlayerNamesAreNotTracked() {
        assertEquals(Optional.empty(), tracker.connected(JAVA, " Notch", "lobby"));
        assertEquals(Optional.empty(), tracker.connected(JAVA, "", "lobby"));
        assertEquals(Optional.empty(), tracker.connected(JAVA, "Notäch", "lobby"));
        assertEquals(Optional.empty(), tracker.connected(JAVA, null, "lobby"));
        assertEquals(Optional.empty(), tracker.disconnected(JAVA));
        // Counter-check: a valid name is tracked.
        assertTrue(tracker.connected(JAVA, "Notch", "lobby").isPresent());
    }

    @Test
    void floodgateUuidsAreBedrockPlayers() {
        PlayerJoin join = (PlayerJoin) tracker.connected(BEDROCK, ".Steve", "lobby").orElseThrow();
        assertTrue(join.bedrock());
        assertTrue(((PlayerQuit) tracker.disconnected(BEDROCK).orElseThrow()).bedrock());
        assertFalse(PlayerTracker.isBedrock(JAVA));
    }

    private static ServiceId id(String value) {
        return new ServiceId(value);
    }
}
