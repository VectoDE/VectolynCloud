package net.vectora.cloud.bridge.legacy;

import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import net.vectora.cloud.bridge.CommandBlocks;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Removes command blocks from a PandaSpigot 1.8.8 server ({@link CommandBlocks}): command blocks
 * ({@code COMMAND}) and command block minecarts ({@code COMMAND_MINECART}) can neither be obtained
 * nor placed nor used, and the ones that exist are removed when their chunk loads (in 1.8.8 a
 * chunk loads its entities with it). Always on, whether or not the cloud started the server; there
 * is no setting to turn it off.
 *
 * <ul>
 *   <li>Obtaining: the creative inventory (also its pick block), picking up, hoppers and
 *       dispensers are refused. Items that still get into an inventory ({@code /give}, a plugin
 *       through the API) are taken away by the sweep of every online player each
 *       {@value #PLAYER_SWEEP_TICKS} ticks, at join and when a container opens. 1.8.8 has no loot
 *       tables or trades that hand out command blocks, nor events for them.</li>
 *   <li>Placing and using: refused; a command block someone clicks is removed.</li>
 *   <li>Existing ones: removed on chunk load, and every {@value #WORLD_SWEEP_TICKS} ticks in all
 *       loaded chunks (blocks a command or plugin set without an event). The bridge is enabled
 *       before the worlds load, so their first chunks go through the chunk load event too. Each
 *       removal is logged with world and position.</li>
 * </ul>
 */
final class CommandBlockGuard implements Listener {

    /** How often the inventories of the online players are checked. */
    static final long PLAYER_SWEEP_TICKS = 5;
    /** How often all loaded chunks are checked. */
    static final long WORLD_SWEEP_TICKS = 100;

    private final CommandBlocks log;

    CommandBlockGuard(CommandBlocks log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    static boolean forbidden(Material material) {
        return material != null && CommandBlocks.forbidden(material.name());
    }

    static boolean forbidden(ItemStack item) {
        return item != null && forbidden(item.getType());
    }

    /**
     * Removes the command blocks among the block entities of a chunk.
     *
     * @param chunk the chunk
     * @param why   for the log
     * @return how many were removed
     */
    int purgeBlocks(Chunk chunk, String why) {
        int removed = 0;
        for (BlockState state : chunk.getTileEntities()) {
            if (forbidden(state.getType())) {
                Block block = state.getBlock();
                block.setType(Material.AIR, false);
                log.blockRemoved(chunk.getWorld().getName(), block.getX(), block.getY(), block.getZ(), why);
                removed++;
            }
        }
        return removed;
    }

    /**
     * Removes command block minecarts, dropped command block items and command blocks in item
     * frames.
     *
     * @param entities the entities
     * @param why      for the log
     * @return how many were removed
     */
    int purgeEntities(Iterable<? extends Entity> entities, String why) {
        int removed = 0;
        for (Entity entity : entities) {
            if (entity instanceof CommandMinecart) {
                Location at = entity.getLocation();
                entity.remove();
                log.minecartRemoved(worldName(at.getWorld()), at.getBlockX(), at.getBlockY(), at.getBlockZ(), why);
                removed++;
            } else if (entity instanceof Item && forbidden(((Item) entity).getItemStack())) {
                entity.remove();
                log.itemsRemoved(1, "the ground in world " + worldName(entity.getWorld()));
                removed++;
            } else if (entity instanceof ItemFrame && forbidden(((ItemFrame) entity).getItem())) {
                ((ItemFrame) entity).setItem(null);
                log.itemsRemoved(1, "an item frame in world " + worldName(entity.getWorld()));
                removed++;
            }
        }
        return removed;
    }

    /** Purges a chunk: its block entities and its entities. */
    void purge(Chunk chunk, String why) {
        purgeBlocks(chunk, why);
        purgeEntities(Arrays.asList(chunk.getEntities()), why);
    }

    /** Purges every chunk that is loaded now (the periodic sweep). */
    void purgeLoaded(Iterable<World> worlds, String why) {
        for (World world : worlds) {
            for (Chunk chunk : world.getLoadedChunks()) {
                purge(chunk, why);
            }
        }
    }

    /**
     * Takes every command block item from an inventory.
     *
     * @param inventory the inventory
     * @return how many stacks were removed
     */
    static int strip(Inventory inventory) {
        int removed = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (forbidden(inventory.getItem(slot))) {
                inventory.setItem(slot, null);
                removed++;
            }
        }
        if (inventory instanceof PlayerInventory) {
            // The armour slots are not part of the 36 slots of a 1.8.8 player inventory.
            PlayerInventory player = (PlayerInventory) inventory;
            ItemStack[] armour = player.getArmorContents();
            boolean changed = false;
            for (int i = 0; i < armour.length; i++) {
                if (forbidden(armour[i])) {
                    armour[i] = null;
                    changed = true;
                    removed++;
                }
            }
            if (changed) {
                player.setArmorContents(armour);
            }
        }
        return removed;
    }

    /** Takes every command block item from a player: inventory, armour, item on the cursor, ender chest. */
    void sweep(HumanEntity player) {
        int removed = strip(player.getInventory()) + strip(player.getEnderChest());
        if (forbidden(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
            removed++;
        }
        log.itemsRemoved(removed, "a player's inventory in world " + worldName(player.getWorld()));
    }

    /** In 1.8.8 the entities come with the chunk. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkLoad(ChunkLoadEvent event) {
        purge(event.getChunk(), "on chunk load");
    }

    /** {@code /summon}, spawn eggs and plugins, where the server calls the event. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpawn(EntitySpawnEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof CommandMinecart || entity instanceof Item && forbidden(((Item) entity).getItemStack())) {
            event.setCancelled(true);
        }
    }

    /** A minecart however it was made (1.8.8 cannot cancel this event): removed at once. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onVehicleCreate(VehicleCreateEvent event) {
        if (event.getVehicle() instanceof CommandMinecart) {
            purgeEntities(Collections.singletonList(event.getVehicle()), "when created");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        sweep(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onOpen(InventoryOpenEvent event) {
        log.itemsRemoved(strip(event.getInventory()), "an opened container in world "
                + worldName(event.getPlayer().getWorld()));
        sweep(event.getPlayer());
    }

    /** Every click, the creative inventory ({@code InventoryCreativeEvent}, also pick block) included. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (forbidden(event.getCurrentItem()) || forbidden(event.getCursor())) {
            event.setCancelled(true);
            if (event.getClickedInventory() != null) {
                log.itemsRemoved(strip(event.getClickedInventory()), "an inventory in world "
                        + worldName(event.getWhoClicked().getWorld()));
            }
            sweep(event.getWhoClicked());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (forbidden(event.getOldCursor())) {
            event.setCancelled(true);
            sweep(event.getWhoClicked());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(PlayerPickupItemEvent event) {
        if (forbidden(event.getItem().getItemStack())) {
            event.setCancelled(true);
            event.getItem().remove();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (forbidden(event.getItem().getItemStack())) {
            event.setCancelled(true);
            event.getItem().remove();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(InventoryMoveItemEvent event) {
        if (forbidden(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDispense(BlockDispenseEvent event) {
        if (forbidden(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (forbidden(event.getItemDrop().getItemStack())) {
            event.getItemDrop().remove();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        if (forbidden(event.getBlockPlaced().getType()) || forbidden(event.getItemInHand())) {
            event.setCancelled(true);
            sweep(event.getPlayer());
        }
    }

    /** Using a command block (its window) or a command block item, e.g. a minecart on a rail. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        if (clicked != null && forbidden(clicked.getType())) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            clicked.setType(Material.AIR, false);
            log.blockRemoved(worldName(clicked.getWorld()), clicked.getX(), clicked.getY(), clicked.getZ(), "when used");
        }
        if (forbidden(event.getItem())) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            sweep(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof CommandMinecart) {
            event.setCancelled(true);
            purgeEntities(Collections.singletonList(event.getRightClicked()), "when used");
        }
    }

    private static String worldName(World world) {
        return world == null ? "?" : world.getName();
    }
}
