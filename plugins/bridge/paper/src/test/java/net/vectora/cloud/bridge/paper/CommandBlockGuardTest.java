package net.vectora.cloud.bridge.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
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
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The command block guard on the real paper-api 26.3 types: a test world with command blocks of
 * every kind and a command block minecart is cleaned when its chunk and entities load. No field or
 * method signature names a paper-api type (class file version 69), see {@link KickSourceTest}.
 */
class CommandBlockGuardTest {

    private final List<String> log = new ArrayList<>();
    /** What the fakes were asked to do, e.g. {@code "air 1,64,2"} or {@code "remove minecart"}. */
    private final List<String> changes = new ArrayList<>();

    private static Object fake(Class<?> type, Answer answer) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> type.getSimpleName();
                    default -> answer.answer(method.getName(), args == null ? new Object[0] : args);
                });
    }

    @FunctionalInterface
    private interface Answer {
        Object answer(String method, Object[] args);
    }

    private Object world() {
        return fake(World.class, (method, args) -> switch (method) {
            case "getName" -> "world";
            default -> throw new UnsupportedOperationException(method);
        });
    }

    /** A block entity of the test world: its block, which records being set to air, and its state. */
    private Object[] blockEntity(Object world, String material, Class<?> stateType, int x, int y, int z) {
        Object[] type = {Material.valueOf(material)};
        Object block = fake(Block.class, (method, args) -> switch (method) {
            case "getType" -> type[0];
            case "getX" -> x;
            case "getY" -> y;
            case "getZ" -> z;
            case "getWorld" -> world;
            case "setType" -> {
                type[0] = args[0];
                changes.add(((Material) args[0]).name().toLowerCase(java.util.Locale.ROOT) + " " + x + "," + y + "," + z);
                yield null;
            }
            default -> throw new UnsupportedOperationException(method);
        });
        Object state = fake(stateType, (method, args) -> switch (method) {
            case "getBlock" -> block;
            case "getType" -> type[0];
            default -> throw new UnsupportedOperationException(method);
        });
        return new Object[] {block, state};
    }

    private Object entity(Class<?> type, Object world, String name) {
        return fake(type, (method, args) -> switch (method) {
            case "getLocation" -> new Location((World) world, 5.5, 70, -5.5);
            case "getWorld" -> world;
            case "remove" -> {
                changes.add("remove " + name);
                yield null;
            }
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private Object chunk(Object world, List<Object[]> blockEntities, List<Object> entities) {
        return fake(Chunk.class, (method, args) -> switch (method) {
            case "getWorld" -> world;
            case "getTileEntities" -> {
                @SuppressWarnings("unchecked")
                Predicate<Object> filter = args.length == 2 ? (Predicate<Object>) args[0] : block -> true;
                List<Object> states = new ArrayList<>();
                for (Object[] entry : blockEntities) {
                    if (filter.test(entry[0])) {
                        states.add(entry[1]);
                    }
                }
                yield args.length == 2 ? states : states.toArray(new BlockState[0]);
            }
            case "isEntitiesLoaded" -> true;
            case "getEntities" -> entities.toArray(new Entity[0]);
            default -> throw new UnsupportedOperationException(method);
        });
    }

    /** The guard; typed {@code Object}, as its class implements a paper-api interface. */
    private Object guard() {
        return new CommandBlockGuard(new CommandBlocks(log::add));
    }

    @Test
    @Tag("requires-java25") // loads paper-api 26.3 classes (class file version 69)
    void aTestWorldIsCleanedWhenItsChunkAndEntitiesLoad() {
        Object world = world();
        List<Object[]> blockEntities = List.of(
                blockEntity(world, "COMMAND_BLOCK", CommandBlock.class, 1, 64, 2),
                blockEntity(world, "CHAIN_COMMAND_BLOCK", CommandBlock.class, 1, 65, 2),
                blockEntity(world, "REPEATING_COMMAND_BLOCK", CommandBlock.class, -3, 10, 7),
                blockEntity(world, "CHEST", Chest.class, 0, 64, 0));
        Object minecart = entity(CommandMinecart.class, world, "command minecart");
        Object rideable = entity(RideableMinecart.class, world, "rideable minecart");
        Object chunk = chunk(world, blockEntities, List.of(minecart, rideable));
        CommandBlockGuard guard = (CommandBlockGuard) guard();

        guard.onChunkLoad(new ChunkLoadEvent((Chunk) chunk, false));
        @SuppressWarnings("unchecked")
        List<Entity> loaded = (List<Entity>) (List<?>) List.of(minecart, rideable);
        guard.onEntitiesLoad(new EntitiesLoadEvent((Chunk) chunk, loaded));

        assertEquals(List.of("air 1,64,2", "air 1,65,2", "air -3,10,7", "remove command minecart"), changes,
                "the chest and the rideable minecart stay");
        assertEquals(List.of(
                "Removed a command block in world world at 1, 64, 2 (on chunk load)",
                "Removed a command block in world world at 1, 65, 2 (on chunk load)",
                "Removed a command block in world world at -3, 10, 7 (on chunk load)",
                "Removed a command block minecart in world world at 5, 70, -6 (on entity load)"), log);

        // Loaded again, the cleaned world has nothing left to remove.
        changes.clear();
        guard.onChunkLoad(new ChunkLoadEvent((Chunk) chunk, false));
        assertEquals(List.of(), changes);
    }

    @Test
    @Tag("requires-java25") // loads paper-api 26.3 classes (class file version 69)
    void chunksLoadedBeforeThePluginAreCleanedToo() {
        Object world = world();
        Object chunk = chunk(world, List.<Object[]>of(blockEntity(world, "COMMAND_BLOCK", CommandBlock.class, 8, 70, 8)),
                List.of(entity(CommandMinecart.class, world, "command minecart")));
        Object withChunks = fake(World.class, (method, args) -> switch (method) {
            case "getName" -> "world";
            case "getLoadedChunks" -> new Chunk[] {(Chunk) chunk};
            default -> throw new UnsupportedOperationException(method);
        });
        @SuppressWarnings("unchecked")
        List<World> worlds = (List<World>) (List<?>) List.of(withChunks);
        ((CommandBlockGuard) guard()).purgeLoaded(worlds, "already loaded at start");
        assertEquals(List.of("air 8,70,8", "remove command minecart"), changes);
        assertEquals(2, log.size());
        assertTrue(log.get(0).endsWith("(already loaded at start)"));
    }

    @Test
    @Tag("requires-java25") // loads paper-api 26.3 classes (class file version 69)
    void aCommandBlockMinecartCannotBeSpawnedOrUsed() {
        Object world = world();
        Object minecart = entity(CommandMinecart.class, world, "command minecart");
        EntitySpawnEvent spawn = new EntitySpawnEvent((Entity) minecart);
        CommandBlockGuard guard = (CommandBlockGuard) guard();
        guard.onSpawn(spawn);
        assertTrue(spawn.isCancelled(), "summon, spawn egg, dispenser, plugin");

        Object rideable = entity(RideableMinecart.class, world, "rideable minecart");
        EntitySpawnEvent other = new EntitySpawnEvent((Entity) rideable);
        guard.onSpawn(other);
        assertFalse(other.isCancelled(), "counter-check: other minecarts spawn");

        Object player = fake(Player.class, (method, args) -> {
            throw new UnsupportedOperationException(method);
        });
        PlayerInteractEntityEvent use = new PlayerInteractEntityEvent((Player) player, (Entity) minecart);
        guard.onInteractEntity(use);
        assertTrue(use.isCancelled());
        assertEquals(List.of("remove command minecart"), changes);
    }

    @Test
    @Tag("requires-java25") // loads paper-api 26.3 classes (class file version 69)
    void everyCommandMaterialOfPaperIsForbidden() {
        List<String> commands = Arrays.stream(Material.values()).map(Material::name)
                .filter(name -> name.contains("COMMAND")).toList();
        assertFalse(commands.isEmpty());
        for (String name : commands) {
            assertTrue(CommandBlockGuard.forbidden(Material.valueOf(name)), name);
        }
        assertFalse(CommandBlockGuard.forbidden(Material.valueOf("STRUCTURE_BLOCK")));
        assertFalse(CommandBlockGuard.forbidden(Material.valueOf("MINECART")));
    }
}
