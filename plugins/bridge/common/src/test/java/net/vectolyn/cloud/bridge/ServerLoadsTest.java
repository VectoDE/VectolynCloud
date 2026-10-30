package net.vectolyn.cloud.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ServerLoadsTest {

    private Instant now = Instant.parse("2026-10-29T12:00:00Z");
    private final ServerLoads loads = new ServerLoads(new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    });
    private final Map<String, Integer> players = new HashMap<>();
    private final Set<String> listed = new java.util.HashSet<>(Set.of("lobby", "lobby-2", "lobby-3", "bedwars-1"));

    private Optional<String> choose(String group, Set<String> exclude) {
        return loads.choose(UUID.randomUUID(), group, exclude, listed::contains, name -> players.getOrDefault(name, 0));
    }

    private void lobbies() {
        loads.registered("lobby", "lobby");
        loads.registered("lobby-2", "lobby");
        loads.registered("bedwars-1", "bedwars");
        loads.capacity("lobby", 50, true);
        loads.capacity("lobby-2", 50, true);
        loads.capacity("bedwars-1", 8, true);
    }

    @Test
    void choosesTheLowestLoadInPercentNotTheFewestPlayers() {
        loads.registered("lobby", "lobby");
        loads.registered("lobby-2", "lobby");
        loads.capacity("lobby", 100, true);
        loads.capacity("lobby-2", 20, true);
        players.put("lobby", 40);   // 40 %
        players.put("lobby-2", 10); // 50 %
        assertEquals(Optional.of("lobby"), choose("lobby", Set.of()));
        players.put("lobby", 60);   // 60 %: now lobby-2 is emptier
        assertEquals(Optional.of("lobby-2"), choose("lobby", Set.of()));
    }

    @Test
    void onlyServersOfTheGroupThatAreKnownOpenListedAndNotExcluded() {
        lobbies();
        players.put("lobby", 10);
        players.put("lobby-2", 1);
        assertEquals(Optional.of("lobby-2"), choose("lobby", Set.of()));
        assertEquals(Optional.of("lobby"), choose("lobby", Set.of("lobby-2")), "the kicked-from server is excluded");
        loads.capacity("lobby-2", 50, false);
        assertEquals(Optional.of("lobby"), choose("lobby", Set.of()), "a closed server gets nobody");
        loads.capacity("lobby-2", 50, true);
        listed.remove("lobby-2");
        assertEquals(Optional.of("lobby"), choose("lobby", Set.of()), "not in the proxy's list");
        loads.registered("lobby-3", "lobby");
        assertEquals(Optional.of("lobby"), choose("lobby", Set.of()), "no slots known yet");
        assertEquals(Optional.empty(), choose("survival", Set.of()));
        assertEquals(Optional.of("bedwars-1"), choose("bedwars", Set.of()));
        loads.removed("lobby");
        assertEquals(Optional.empty(), choose("lobby", Set.of("lobby-2")));
    }

    @Test
    void theGroupOfARemovedServerIsKnownForAWhile() {
        lobbies();
        loads.removed("bedwars-1");
        assertEquals(Optional.of("bedwars"), loads.groupOf("bedwars-1"), "the kicks of its players come later");
        assertFalse(loads.isOpen("bedwars-1"), "but nobody is sent there");
        assertEquals(Optional.empty(), choose("bedwars", Set.of()));
        now = now.plus(ServerLoads.REMEMBER_GROUP).minusSeconds(1);
        assertEquals(Optional.of("bedwars"), loads.groupOf("bedwars-1"), "59 s");
        now = now.plusSeconds(1);
        assertEquals(Optional.empty(), loads.groupOf("bedwars-1"), "60 s: forgotten");
        // Counter-check: registered again under another group, the new one counts at once.
        loads.removed("lobby-2");
        loads.registered("lobby-2", "hub");
        assertEquals(Optional.of("hub"), loads.groupOf("lobby-2"));
        loads.removed("lobby-2");
        assertEquals(Optional.of("hub"), loads.groupOf("lobby-2"));
    }

    @Test
    void aFullServerIsSkippedPlayersOnTheirWayIncluded() {
        loads.registered("lobby", "lobby");
        loads.registered("lobby-2", "lobby");
        loads.capacity("lobby", 2, true);
        loads.capacity("lobby-2", 10, true);
        players.put("lobby", 1);   // 50 %
        players.put("lobby-2", 9); // 90 %
        UUID first = UUID.randomUUID();
        assertEquals(Optional.of("lobby"),
                loads.choose(first, "lobby", Set.of(), listed::contains, name -> players.getOrDefault(name, 0)),
                "counter-check: one slot left, lowest load");
        assertEquals(Optional.of("lobby-2"), choose("lobby", Set.of()),
                "lobby is full with the player on his way, although the proxy still sees 1 of 2");
        assertEquals(Optional.empty(), choose("lobby", Set.of()), "lobby-2 is full too (9 + 1 on the way)");
        loads.arrived(first);
        players.put("lobby", 2);
        assertEquals(Optional.empty(), choose("lobby", Set.of()), "players at the slots: full");
        players.put("lobby", 5);
        assertEquals(Optional.empty(), choose("lobby", Set.of()), "above the slots: full");
        // Counter-check: one player leaves lobby, it takes the next one again.
        players.put("lobby", 1);
        assertEquals(Optional.of("lobby"), choose("lobby", Set.of()));
    }

    @Test
    void capacityForAnUnknownServerIsIgnored() {
        assertFalse(loads.capacity("lobby-9", 50, true));
        assertFalse(loads.isOpen("lobby-9"));
        loads.registered("lobby-9", "lobby");
        assertFalse(loads.isOpen("lobby-9"));
        assertTrue(loads.capacity("lobby-9", 50, true));
        assertTrue(loads.isOpen("lobby-9"));
    }

    @Test
    void playersOnTheirWayCountUntilTheyArriveOrTenSecondsPass() {
        lobbies();
        // Both empty: a burst of logins alternates instead of piling onto one server.
        assertEquals(Optional.of("lobby"), choose("lobby", Set.of()));
        assertEquals(Optional.of("lobby-2"), choose("lobby", Set.of()));
        UUID third = UUID.randomUUID();
        assertEquals(Optional.of("lobby"),
                loads.choose(third, "lobby", Set.of(), listed::contains, name -> players.getOrDefault(name, 0)));
        loads.arrived(third);
        assertEquals(Optional.of("lobby"), choose("lobby", Set.of()), "arrived players count through the proxy only");
        now = now.plus(ServerLoads.PENDING);
        assertEquals(Optional.of("lobby"), choose("lobby", Set.of()), "pending ones expired");
    }

    @Test
    void equalLoadPrefersFewerPlayersThenTheName() {
        loads.registered("lobby", "lobby");
        loads.registered("lobby-2", "lobby");
        loads.capacity("lobby", 100, true);
        loads.capacity("lobby-2", 50, true);
        players.put("lobby", 20);
        players.put("lobby-2", 10);
        assertEquals(Optional.of("lobby-2"), choose("lobby", Set.of()));
        now = now.plus(Duration.ofMinutes(1));
        loads.capacity("lobby", 50, true);
        players.put("lobby", 10);
        assertEquals(Optional.of("lobby"), loads.choose(UUID.randomUUID(), "lobby", Set.of(), listed::contains,
                name -> players.getOrDefault(name, 0)));
    }

    @Test
    void transferTextFollowsTheClientLanguage() {
        String german = TransferMessages.text(net.vectolyn.protocol.message.TransferPlayer.Reason.SCALE_DOWN,
                java.util.Locale.GERMANY);
        String english = TransferMessages.text(net.vectolyn.protocol.message.TransferPlayer.Reason.SCALE_DOWN,
                java.util.Locale.US);
        assertTrue(german.startsWith("Dieser Server"), german);
        assertTrue(english.startsWith("This server"), english);
        assertEquals(english, TransferMessages.text(net.vectolyn.protocol.message.TransferPlayer.Reason.SCALE_DOWN, null));
        assertEquals(german, TransferMessages.text(net.vectolyn.protocol.message.TransferPlayer.Reason.SCALE_DOWN,
                java.util.Locale.of("de", "AT")));
    }

    @Test
    void onlyAServerWithSlotsThatTheCloudClosedCountsAsClosed() {
        assertFalse(loads.isClosed("lobby"), "unknown");
        loads.registered("lobby", "lobby");
        assertFalse(loads.isClosed("lobby"), "no slots known yet");
        loads.capacity("lobby", 50, true);
        assertFalse(loads.isClosed("lobby"), "open");
        loads.capacity("lobby", 50, false);
        assertTrue(loads.isClosed("lobby"), "closed");
        loads.removed("lobby");
        assertFalse(loads.isClosed("lobby"), "removed");
    }

    @Test
    void rescueLostAndClosedTextsFollowTheClientLanguage() {
        java.util.List<java.util.function.Function<java.util.Locale, String>> texts =
                java.util.List.of(TransferMessages::rescued, TransferMessages::lost, TransferMessages::closed);
        for (var text : texts) {
            String german = text.apply(java.util.Locale.GERMANY);
            String english = text.apply(java.util.Locale.US);
            assertFalse(german.equals(english), german);
            assertEquals(english, text.apply(null));
            assertEquals(german, text.apply(java.util.Locale.of("de", "CH")));
        }
    }
}
