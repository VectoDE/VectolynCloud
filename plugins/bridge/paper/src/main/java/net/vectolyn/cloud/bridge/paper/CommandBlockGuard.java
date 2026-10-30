package net.vectolyn.cloud.bridge.paper;

import io.papermc.paper.event.player.PlayerPickBlockEvent;
import io.papermc.paper.event.player.PlayerPickItemEvent;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.vectolyn.cloud.bridge.CommandBlocks;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.entity.VillagerReplenishTradeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Removes command blocks from a Paper server ({@link CommandBlocks}): command blocks, chain and
 * repeating command blocks and command block minecarts can neither be obtained nor placed nor
 * used, and the ones that exist are removed when their chunk or its entities load. Always on,
 * whether or not the cloud started the server; there is no setting to turn it off.
 *
 * <ul>
 *   <li>Obtaining: the creative inventory, pick block, loot, villager trades, picking up, hoppers
 *       and dispensers are refused. Items that still get into an inventory ({@code /give}, a
 *       plugin through the API) are taken away by the sweep of every online player each
 *       {@value #PLAYER_SWEEP_TICKS} ticks, at join and when a container opens.</li>
 *   <li>Placing and using: refused; a command block someone clicks is removed.</li>
 *   <li>Existing ones: removed on chunk and entity load, for the chunks loaded before the plugin
 *       at {@link #purgeLoaded}, and every {@value #WORLD_SWEEP_TICKS} ticks in all loaded chunks
 *       (blocks a command or plugin set without an event). Each removal is logged with world and
 *       position.</li>
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
        for (BlockState state : chunk.getTileEntities(block -> forbidden(block.getType()), false)) {
            Block block = state.getBlock();
            block.setType(Material.AIR, false);
            log.blockRemoved(chunk.getWorld().getName(), block.getX(), block.getY(), block.getZ(), why);
            removed++;
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
            } else if (entity instanceof Item item && forbidden(item.getItemStack())) {
                item.remove();
                log.itemsRemoved(1, "the ground in world " + worldName(item.getWorld()));
                removed++;
            } else if (entity instanceof ItemFrame frame && forbidden(frame.getItem())) {
                frame.setItem(null, false);
                log.itemsRemoved(1, "an item frame in world " + worldName(frame.getWorld()));
                removed++;
            }
        }
        return removed;
    }

    /** Purges every chunk that is loaded now: the ones loaded before the plugin, and the periodic sweep. */
    void purgeLoaded(Iterable<World> worlds, String why) {
        for (World world : worlds) {
            for (Chunk chunk : world.getLoadedChunks()) {
                purgeBlocks(chunk, why);
                if (chunk.isEntitiesLoaded()) {
                    purgeEntities(Arrays.asList(chunk.getEntities()), why);
                }
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
        return removed;
    }

    /** Takes every command block item from a player: inventory, item on the cursor, ender chest. */
    void sweep(Player player) {
        int removed = strip(player.getInventory()) + strip(player.getEnderChest());
        if (forbidden(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
            removed++;
        }
        log.itemsRemoved(removed, "a player's inventory in world " + worldName(player.getWorld()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkLoad(ChunkLoadEvent event) {
        purgeBlocks(event.getChunk(), "on chunk load");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        purgeEntities(event.getEntities(), "on entity load");
    }

    /** {@code /summon}, spawn eggs, dispensers, plugins; {@code ItemSpawnEvent} is a subclass. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpawn(EntitySpawnEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof CommandMinecart || entity instanceof Item item && forbidden(item.getItemStack())) {
            event.setCancelled(true);
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
        if (event.getPlayer() instanceof Player player) {
            sweep(player);
        }
    }

    /** Every click, the creative inventory ({@code InventoryCreativeEvent}) included. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (forbidden(event.getCurrentItem()) || forbidden(event.getCursor())) {
            event.setCancelled(true);
            if (event.getClickedInventory() != null) {
                log.itemsRemoved(strip(event.getClickedInventory()), "an inventory in world "
                        + worldName(event.getWhoClicked().getWorld()));
            }
            if (event.getWhoClicked() instanceof Player player) {
                sweep(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (forbidden(event.getOldCursor())) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                sweep(player);
            }
        }
    }

    /** Creative pick block and pick item. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPick(PlayerPickItemEvent event) {
        boolean block = event instanceof PlayerPickBlockEvent pick && forbidden(pick.getBlock().getType());
        if (block || forbidden(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLoot(LootGenerateEvent event) {
        event.getLoot().removeIf(CommandBlockGuard::forbidden);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTrade(VillagerAcquireTradeEvent event) {
        if (forbidden(event.getRecipe().getResult())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onReplenish(VillagerReplenishTradeEvent event) {
        if (forbidden(event.getRecipe().getResult())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
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
            purgeEntities(List.of(event.getRightClicked()), "when used");
        }
    }

    private static String worldName(World world) {
        return world == null ? "?" : world.getName();
    }
}
