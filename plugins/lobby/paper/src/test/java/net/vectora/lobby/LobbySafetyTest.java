package net.vectora.lobby;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.junit.jupiter.api.Test;

/** The lobby must stay free of mobs: only plugins may create creatures. */
class LobbySafetyTest {

    @Test
    void naturalAndIncidentalSpawnsAreBlocked() {
        for (SpawnReason reason : SpawnReason.values()) {
            if (reason == SpawnReason.CUSTOM) continue;
            assertTrue(VectoraLobby.blocksSpawn(reason), reason + " must be blocked");
        }
    }

    @Test
    void pluginCreatedCreaturesAreAllowed() {
        assertFalse(VectoraLobby.blocksSpawn(SpawnReason.CUSTOM));
    }
}
