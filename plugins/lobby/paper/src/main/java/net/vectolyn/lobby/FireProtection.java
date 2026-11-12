// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.lobby;

import java.util.logging.Logger;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockIgniteEvent.IgniteCause;

/**
 * No fire starts or burns anything in the lobby. The game rules stop fire from spreading
 * ({@code fire_spread_radius_around_player} 0) and from hurting anyone ({@code fire_damage}); a
 * rule does not stop a fire from starting, and with the spread rule at 0 a fire that is there does
 * not go out by itself. So every ignition is cancelled, whatever lit it (flint and steel, lava,
 * lightning, a fireball, an explosion, an arrow), and no block burns away.
 *
 * <p>Commands such as {@code /setblock} place a block without any event, so they are not covered.
 */
final class FireProtection implements Listener {

    private final Logger logger;

    FireProtection(Logger logger) {
        this.logger = logger;
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        event.setCancelled(true);
        // Spreading is stopped by the rule and would fill the log if it were not.
        if (event.getCause() != IgniteCause.SPREAD) {
            Block block = event.getBlock();
            logger.info("Fire blocked: " + event.getCause().name() + " at " + block.getX() + " " + block.getY()
                    + " " + block.getZ());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        event.setCancelled(true);
    }
}
