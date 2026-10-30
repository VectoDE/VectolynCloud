package net.vectora.cloud.bridge.legacy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.vectora.cloud.bridge.CommandBlocks;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.CommandBlock;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

/**
 * The command block guard on the real PandaSpigot 1.8.8 types: a test world with command blocks
 * and a command block minecart is cleaned when its chunk loads, and command block items are taken
 * out of inventories.
 */
class CommandBlockGuardTest {

    private final List<String> log = new ArrayList<>();
    /** What the fakes were asked to do, e.g. {@code "air 1,64,2"} or {@code "remove command minecart"}. */
    private final List<String> changes = new ArrayList<>();
    private final CommandBlockGuard guard = new CommandBlockGuard(new CommandBlocks(log::add));

    @FunctionalInterface
    private interface Answer {
        Object answer(String method, Object[] args);
    }

    private static <T> T fake(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return type.getSimpleName();
                default:
                    return answer.answer(method.getName(), args == null ? new Object[0] : args);
            }
        }));
    }

    private static World world() {
        return fake(World.class, (method, args) -> {
            if (method.equals("getName")) {
                return "world";
            }
            throw new UnsupportedOperationException(method);
        });
    }

    private BlockState blockEntity(World world, Material material, Class<? extends BlockState> stateType, int x, int y,
                                   int z) {
        Material[] type = {material};
        Block block = fake(Block.class, (method, args) -> {
            switch (method) {
                case "getType":
                    return type[0];
                case "getX":
                    return x;
                case "getY":
                    return y;
                case "getZ":
                    return z;
                case "getWorld":
                    return world;
                case "setType":
                    type[0] = (Material) args[0];
                    changes.add(type[0].name().toLowerCase(Locale.ROOT) + " " + x + "," + y + "," + z);
                    return null;
                default:
                    throw new UnsupportedOperationException(method);
            }
        });
        return fake(stateType, (method, args) -> {
            switch (method) {
                case "getBlock":
                    return block;
                case "getType":
                    return type[0];
                default:
                    throw new UnsupportedOperationException(method);
            }
        });
    }

    private <T extends Entity> T entity(Class<T> type, World world, String name) {
        return fake(type, (method, args) -> {
            switch (method) {
                case "getLocation":
                    return new Location(world, 5.5, 70, -5.5);
                case "getWorld":
                    return world;
                case "remove":
                    changes.add("remove " + name);
                    return null;
                default:
                    throw new UnsupportedOperationException(method);
            }
        });
    }

    private Item droppedItem(World world, ItemStack stack) {
        return fake(Item.class, (method, args) -> {
            switch (method) {
                case "getItemStack":
                    return stack;
                case "getWorld":
                    return world;
                case "remove":
                    changes.add("remove item " + stack.getType().name().toLowerCase(Locale.ROOT));
                    return null;
                default:
                    throw new UnsupportedOperationException(method);
            }
        });
    }

    private static Chunk chunk(World world, List<BlockState> blockEntities, List<Entity> entities) {
        return fake(Chunk.class, (method, args) -> {
            switch (method) {
                case "getWorld":
                    return world;
                case "getTileEntities":
                    return blockEntities.toArray(new BlockState[0]);
                case "getEntities":
                    return entities.toArray(new Entity[0]);
                default:
                    throw new UnsupportedOperationException(method);
            }
        });
    }

    @Test
    void aTestWorldIsCleanedWhenItsChunkLoads() {
        World world = world();
        List<BlockState> blockEntities = Arrays.asList(
                blockEntity(world, Material.COMMAND, CommandBlock.class, 1, 64, 2),
                blockEntity(world, Material.COMMAND, CommandBlock.class, -3, 10, 7),
                blockEntity(world, Material.CHEST, Chest.class, 0, 64, 0));
        List<Entity> entities = Arrays.asList(
                entity(CommandMinecart.class, world, "command minecart"),
                entity(RideableMinecart.class, world, "rideable minecart"),
                droppedItem(world, new ItemStack(Material.COMMAND_MINECART)),
                droppedItem(world, new ItemStack(Material.STONE)));
        Chunk chunk = chunk(world, blockEntities, entities);

        guard.onChunkLoad(new ChunkLoadEvent(chunk, false));

        assertEquals(Arrays.asList("air 1,64,2", "air -3,10,7", "remove command minecart", "remove item command_minecart"),
                changes, "the chest, the rideable minecart and the stone stay");
        assertEquals(Arrays.asList(
                "Removed a command block in world world at 1, 64, 2 (on chunk load)",
                "Removed a command block in world world at -3, 10, 7 (on chunk load)",
                "Removed a command block minecart in world world at 5, 70, -6 (on chunk load)",
                "Removed 1 command block item from the ground in world world"), log);

        changes.clear();
        guard.purgeBlocks(chunk, "on chunk load");
        assertEquals(Arrays.asList(), changes, "loaded again, the cleaned world has nothing left to remove");
    }

    @Test
    void theSweepCleansEveryLoadedChunk() {
        World plain = world();
        Chunk chunk = chunk(plain, Arrays.asList(blockEntity(plain, Material.COMMAND, CommandBlock.class, 8, 70, 8)),
                Arrays.<Entity>asList(entity(CommandMinecart.class, plain, "command minecart")));
        World world = fake(World.class, (method, args) -> {
            switch (method) {
                case "getName":
                    return "world";
                case "getLoadedChunks":
                    return new Chunk[] {chunk};
                default:
                    throw new UnsupportedOperationException(method);
            }
        });
        guard.purgeLoaded(Arrays.asList(world), "found by the sweep");
        assertEquals(Arrays.asList("air 8,70,8", "remove command minecart"), changes);
        assertTrue(log.get(0).endsWith("(found by the sweep)"));
    }

    @Test
    void aCommandBlockMinecartCannotBeSpawnedCreatedOrUsed() {
        World world = world();
        CommandMinecart minecart = entity(CommandMinecart.class, world, "command minecart");
        EntitySpawnEvent spawn = new EntitySpawnEvent(minecart);
        guard.onSpawn(spawn);
        assertTrue(spawn.isCancelled());
        EntitySpawnEvent other = new EntitySpawnEvent(entity(RideableMinecart.class, world, "rideable minecart"));
        guard.onSpawn(other);
        assertFalse(other.isCancelled(), "counter-check: other minecarts spawn");

        guard.onVehicleCreate(new VehicleCreateEvent(minecart));
        assertEquals(Arrays.asList("remove command minecart"), changes, "1.8.8 cannot cancel the creation");

        Player player = fake(Player.class, (method, args) -> {
            throw new UnsupportedOperationException(method);
        });
        PlayerInteractEntityEvent use = new PlayerInteractEntityEvent(player, minecart);
        guard.onInteractEntity(use);
        assertTrue(use.isCancelled());
    }

    @Test
    void commandBlockItemsAreTakenOutOfInventoriesAndArmour() {
        ItemStack[] slots = {new ItemStack(Material.COMMAND), new ItemStack(Material.STONE), null,
                new ItemStack(Material.COMMAND_MINECART, 3)};
        ItemStack[] armour = {null, null, null, new ItemStack(Material.COMMAND)};
        PlayerInventory inventory = fake(PlayerInventory.class, (method, args) -> {
            switch (method) {
                case "getSize":
                    return slots.length;
                case "getItem":
                    return slots[(Integer) args[0]];
                case "setItem":
                    slots[(Integer) args[0]] = (ItemStack) args[1];
                    return null;
                case "getArmorContents":
                    return armour.clone();
                case "setArmorContents":
                    System.arraycopy((ItemStack[]) args[0], 0, armour, 0, armour.length);
                    return null;
                default:
                    throw new UnsupportedOperationException(method);
            }
        });
        assertEquals(3, CommandBlockGuard.strip(inventory));
        assertNull(slots[0]);
        assertEquals(Material.STONE, slots[1].getType());
        assertNull(slots[3]);
        assertArrayEquals(new ItemStack[4], armour);

        ItemStack[] chest = {new ItemStack(Material.COMMAND)};
        Inventory container = fake(Inventory.class, (method, args) -> {
            switch (method) {
                case "getSize":
                    return chest.length;
                case "getItem":
                    return chest[(Integer) args[0]];
                case "setItem":
                    chest[(Integer) args[0]] = (ItemStack) args[1];
                    return null;
                default:
                    throw new UnsupportedOperationException(method);
            }
        });
        assertEquals(1, CommandBlockGuard.strip(container));
        assertNull(chest[0]);
    }

    @Test
    void everyCommandMaterialOf188IsForbidden() {
        List<Material> commands = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material.name().contains("COMMAND")) {
                commands.add(material);
            }
        }
        assertEquals(Arrays.asList(Material.COMMAND, Material.COMMAND_MINECART), commands);
        for (Material material : commands) {
            assertTrue(CommandBlockGuard.forbidden(material), material.name());
            assertTrue(CommandBlockGuard.forbidden(new ItemStack(material)), material.name());
        }
        assertFalse(CommandBlockGuard.forbidden(Material.MINECART));
        assertFalse(CommandBlockGuard.forbidden((ItemStack) null));
    }
}
