package net.vectolyn.cloud.download.jre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import net.vectolyn.cloud.download.jre.JavaRequirement.Choice;
import net.vectolyn.cloud.download.jre.JavaRequirement.Reason;
import org.junit.jupiter.api.Test;

class JavaRequirementTest {

    @Test
    void legacyGroupsGetJava21() throws Exception {
        // Mojang lists Java 8 for 1.8.8; PandaSpigot still gets 21 (Netty's Unsafe warning under 25).
        assertEquals(new Choice(21, Reason.LEGACY),
                JavaRequirement.forGroup("pandaspigot", OptionalInt.of(8), 0).choose());
        assertEquals(new Choice(21, Reason.LEGACY),
                JavaRequirement.forGroup("PandaSpigot", OptionalInt.empty(), 0).choose());
    }

    @Test
    void everythingElseGetsJava25() throws Exception {
        assertEquals(new Choice(25, Reason.DEFAULT), JavaRequirement.forGroup("paper", OptionalInt.of(25), 0).choose());
        assertEquals(new Choice(25, Reason.DEFAULT), JavaRequirement.forGroup("velocity", OptionalInt.of(21), 0).choose());
        assertEquals(new Choice(25, Reason.DEFAULT), JavaRequirement.forGroup("fabric", OptionalInt.empty(), 0).choose());
    }

    @Test
    void aHigherMinimumWins() throws Exception {
        assertEquals(new Choice(26, Reason.MINIMUM), JavaRequirement.forGroup("paper", OptionalInt.of(26), 0).choose());
        assertEquals(new Choice(27, Reason.MINIMUM),
                JavaRequirement.forGroup("pandaspigot", OptionalInt.of(27), 0).choose());
    }

    @Test
    void theGroupOverrideWins() throws Exception {
        assertEquals(new Choice(21, Reason.OVERRIDE), JavaRequirement.forGroup("paper", OptionalInt.of(21), 21).choose());
        assertEquals(new Choice(17, Reason.OVERRIDE),
                JavaRequirement.forGroup("pandaspigot", OptionalInt.of(8), 17).choose());
        assertEquals(new Choice(26, Reason.OVERRIDE), JavaRequirement.forGroup("paper", OptionalInt.of(25), 26).choose());
    }

    @Test
    void anOverrideBelowTheMinimumIsRefused() {
        JreException e = assertThrows(JreException.class,
                () -> JavaRequirement.forGroup("paper", OptionalInt.of(25), 21).choose());
        assertTrue(e.getMessage().contains("Java 21"), e.getMessage());
        assertTrue(e.getMessage().contains("needs Java 25 or newer"), e.getMessage());
    }

    @Test
    void minimumsFromSeveralSourcesTakeTheHighest() {
        assertEquals(OptionalInt.of(25), JavaRequirement.minimumOf(OptionalInt.of(21), OptionalInt.empty(), OptionalInt.of(25)));
        assertEquals(OptionalInt.empty(), JavaRequirement.minimumOf(OptionalInt.empty()));
        assertEquals(OptionalInt.empty(), JavaRequirement.minimumOf());
    }

    @Test
    void refusesOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class, () -> JavaRequirement.forGroup("paper", OptionalInt.empty(), 7));
        assertThrows(IllegalArgumentException.class, () -> JavaRequirement.forGroup("paper", OptionalInt.empty(), 100));
        assertThrows(IllegalArgumentException.class, () -> JavaRequirement.forGroup("paper", OptionalInt.of(0), 0));
        assertThrows(IllegalArgumentException.class, () -> JavaRequirement.forGroup("paper", OptionalInt.of(100), 0));
    }
}
