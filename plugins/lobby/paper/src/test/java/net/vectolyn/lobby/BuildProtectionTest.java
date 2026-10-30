package net.vectolyn.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Only staff build in the lobby. No method signature names a paper-api type (class file version
 * 69): JUnit reads the signatures during discovery, also on Java 21.
 */
class BuildProtectionTest {

    /** A player whose permission check answers {@code permitted}. */
    private static Object player(boolean permitted) {
        return Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "hasPermission" -> permitted && BuildProtection.PERMISSION.equals(args[0]);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Player";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Object block() {
        return Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] {Block.class}, (proxy, method, args) -> {
            throw new UnsupportedOperationException(method.getName());
        });
    }

    @Test
    @Tag("requires-java25") // loads paper-api 26.3 classes (class file version 69)
    void aPlayerWithoutThePermissionBreaksAndPlacesNothing() {
        BuildProtection protection = new BuildProtection();
        BlockBreakEvent breaking = new BlockBreakEvent((Block) block(), (Player) player(false));
        protection.onBreak(breaking);
        assertTrue(breaking.isCancelled());
        BlockPlaceEvent placing = new BlockPlaceEvent((Block) block(), null, (Block) block(), null,
                (Player) player(false), true, EquipmentSlot.HAND);
        protection.onPlace(placing);
        assertTrue(placing.isCancelled());
    }

    @Test
    @Tag("requires-java25") // loads paper-api 26.3 classes (class file version 69)
    void staffWithThePermissionBuilds() {
        BuildProtection protection = new BuildProtection();
        BlockBreakEvent breaking = new BlockBreakEvent((Block) block(), (Player) player(true));
        protection.onBreak(breaking);
        assertFalse(breaking.isCancelled());
        BlockPlaceEvent placing = new BlockPlaceEvent((Block) block(), null, (Block) block(), null,
                (Player) player(true), true, EquipmentSlot.HAND);
        protection.onPlace(placing);
        assertFalse(placing.isCancelled());
    }

    @Test
    void thePermissionGoesToOperatorsOnly() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            Map<?, ?> yml = new Yaml().load(in);
            Map<?, ?> permissions = (Map<?, ?>) yml.get("permissions");
            Map<?, ?> build = (Map<?, ?>) permissions.get(BuildProtection.PERMISSION);
            assertEquals("op", build.get("default"));
        }
    }
}
