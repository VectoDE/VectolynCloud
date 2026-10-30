package net.vectolyn.lobby;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The lobby must stay free of mobs: only plugins may create creatures. */
class LobbySafetyTest {

    @ParameterizedTest
    @ValueSource(strings = {"NATURAL", "SPAWNER", "SPAWNER_EGG", "EGG", "BREEDING", "VILLAGE_INVASION", "PATROL", "RAID", "COMMAND", "DEFAULT"})
    void naturalAndIncidentalSpawnsAreBlocked(String reason) {
        assertTrue(SpawnPolicy.blocks(reason), reason + " must be blocked");
    }

    @Test
    void pluginCreatedCreaturesAreAllowed() {
        assertFalse(SpawnPolicy.blocks("CUSTOM"));
    }
}
