package net.vectora.cloud.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommandBlocksTest {

    @ParameterizedTest
    @ValueSource(strings = {"COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK", "COMMAND_BLOCK_MINECART",
            "LEGACY_COMMAND", "LEGACY_COMMAND_CHAIN", "LEGACY_COMMAND_REPEATING", "LEGACY_COMMAND_MINECART",
            "COMMAND", "COMMAND_MINECART", "command_block"})
    void everyKindOfCommandBlockIsForbidden(String material) {
        assertTrue(CommandBlocks.forbidden(material), material);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STONE", "MINECART", "CHEST_MINECART", "STRUCTURE_BLOCK", "JIGSAW", "BARRIER", "DEBUG_STICK",
            "COMMANDER", ""})
    void otherMaterialsAreNot(String material) {
        assertFalse(CommandBlocks.forbidden(material), material);
    }

    @Test
    void nullIsNoCommandBlock() {
        assertFalse(CommandBlocks.forbidden(null));
    }

    @Test
    void removalsAreLoggedWithWorldAndPosition() {
        List<String> lines = new ArrayList<>();
        CommandBlocks blocks = new CommandBlocks(lines::add, () -> 0);
        blocks.blockRemoved("world", 10, 64, -3, "on chunk load");
        blocks.minecartRemoved("world_nether", -1, 70, 2, "on entity load");
        blocks.itemsRemoved(1, "a player's inventory in world world");
        blocks.itemsRemoved(3, "a chest in world world");
        blocks.itemsRemoved(0, "nowhere");
        assertEquals(List.of(
                "Removed a command block in world world at 10, 64, -3 (on chunk load)",
                "Removed a command block minecart in world world_nether at -1, 70, 2 (on entity load)",
                "Removed 1 command block item from a player's inventory in world world",
                "Removed 3 command block items from a chest in world world"), lines);
    }

    @Test
    void aWorldFullOfCommandBlocksCannotFloodTheLog() {
        List<String> lines = new ArrayList<>();
        AtomicLong now = new AtomicLong();
        CommandBlocks blocks = new CommandBlocks(lines::add, now::get);
        for (int i = 0; i < 100; i++) {
            blocks.blockRemoved("world", i, 64, 0, "on chunk load");
        }
        assertEquals(CommandBlocks.LOG_LINES, lines.size());
        now.set(TimeUnit.SECONDS.toNanos(CommandBlocks.LOG_SECONDS));
        blocks.blockRemoved("world", 0, 65, 0, "on chunk load");
        assertEquals("Removed a command block in world world at 0, 65, 0 (on chunk load) (80 more removals not logged)",
                lines.get(lines.size() - 1));
    }
}
