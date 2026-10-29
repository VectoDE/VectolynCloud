package net.vectora.cloud.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A network kick is reported only once its kick event went through: the platform calls
 * {@link OwnKick#passed} from its last-priority handler for events nobody cancelled.
 */
class OwnKickTest {

    private final OwnKick own = new OwnKick();
    private final List<String> reports = new ArrayList<>();
    private final UUID steve = UUID.randomUUID();
    private final UUID alex = UUID.randomUUID();

    /** The bridge's handler at MONITOR, reached only by events no plugin cancelled. */
    private void eventPassed(UUID player) {
        own.passed(player).ifPresent(reason -> reports.add(player.equals(steve) ? "Steve|" + reason : "Alex|" + reason));
    }

    @Test
    void aKickThatGoesThroughIsReportedAfterTheEvent() {
        assertTrue(own.run(steve, "Spam", () -> {
            assertTrue(reports.isEmpty(), "nothing is reported before the event's outcome");
            eventPassed(steve);
        }));
        assertEquals(List.of("Steve|Spam"), reports);
    }

    @Test
    void aKickAPluginCancelsIsNotReported() {
        // The cancelled event never reaches the handler that skips cancelled events.
        assertFalse(own.run(steve, "Spam", () -> { }));
        assertTrue(reports.isEmpty(), "the proxy does not take a player off the network who stays");
    }

    @Test
    void onlyTheOwnKickIsReportedAndOnlyOnce() {
        own.run(steve, "Spam", () -> {
            eventPassed(alex);   // another player's kick in the same moment
            eventPassed(steve);
            eventPassed(steve);  // a second event of the same kick
            assertTrue(own.running(steve));
            assertFalse(own.running(alex));
        });
        assertEquals(List.of("Steve|Spam"), reports);
        assertFalse(own.running(steve), "over after the kick");
        eventPassed(steve);
        assertEquals(1, reports.size(), "a later kick of the player is not the bridge's");
    }

    @Test
    void aKickInsideAKickKeepsBothApart() {
        boolean outer = own.run(steve, "Spam", () -> {
            // A plugin kicks Alex from its handler of Steve's kick event, through networkKick.
            assertFalse(own.run(alex, "Flug", () -> { }), "Alex's kick was cancelled");
            eventPassed(steve);
        });
        assertTrue(outer);
        assertEquals(List.of("Steve|Spam"), reports);
    }
}
