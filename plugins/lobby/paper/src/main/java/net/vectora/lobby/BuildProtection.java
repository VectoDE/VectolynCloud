package net.vectora.lobby;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Only staff build in the lobby: breaking and placing blocks needs {@value #PERMISSION}, which
 * {@code plugin.yml} gives to operators only. The check runs on the server for every block event.
 */
final class BuildProtection implements Listener {

    /** Lets a player break and place blocks in the lobby. */
    static final String PERMISSION = "vectora.lobby.build";

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!event.getPlayer().hasPermission(PERMISSION)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!event.getPlayer().hasPermission(PERMISSION)) {
            event.setCancelled(true);
        }
    }
}
