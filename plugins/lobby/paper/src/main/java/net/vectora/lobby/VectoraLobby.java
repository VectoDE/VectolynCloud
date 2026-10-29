package net.vectora.lobby;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Creates and protects Vectora's compact sky-island lobby on its first startup. */
public final class VectoraLobby extends JavaPlugin implements Listener {
    private static final int Y = 100;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            World world = Bukkit.getWorlds().getFirst();
            configureWorld(world);
            if (!getConfig().getBoolean("built", false)) {
                buildIsland(world);
                getConfig().set("built", true);
                saveConfig();
                getLogger().info("Vectora sky lobby has been created.");
            }
            world.setSpawnLocation(new Location(world, 0.5, Y + 3, 0.5, 180, 0));
        }, 40L);
    }

    private void configureWorld(World world) {
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setTime(1000);
        world.setStorm(false);
    }

    private void buildIsland(World world) {
        for (int x = -27; x <= 27; x++) {
            for (int z = -27; z <= 27; z++) {
                double distance = Math.sqrt(x * x + z * z);
                if (distance > 27) continue;
                int top = Y - Math.max(0, (int) ((distance - 15) / 3));
                for (int y = 87; y <= top; y++) {
                    Material material = y == top ? Material.GRASS_BLOCK : (y < top - 4 ? Material.DEEPSLATE : Material.STONE);
                    set(world, x, y, z, material);
                }
            }
        }

        // Central crystal spawn platform.
        disk(world, 0, Y + 1, 0, 7, Material.POLISHED_DEEPSLATE);
        disk(world, 0, Y + 2, 0, 5, Material.CALCITE);
        disk(world, 0, Y + 3, 0, 3, Material.SMOOTH_QUARTZ);
        for (int y = Y + 4; y <= Y + 9; y++) set(world, 0, y, 0, Material.AMETHYST_BLOCK);
        set(world, 0, Y + 10, 0, Material.SEA_LANTERN);

        // Four intentionally distinct paths, one for each existing backend mode.
        path(world, 0, -6, 0, -23, "SURVIVAL", "Explore • Build • Trade");
        path(world, 6, 0, 23, 0, "CREATIVE", "Create without limits");
        path(world, 0, 6, 0, 23, "MODDED", "Java & Bedrock friendly");
        path(world, -6, 0, -23, 0, "THE END", "A dangerous new realm");

        tree(world, -15, Y + 1, -12, Material.WARPED_WART_BLOCK);
        tree(world, 16, Y + 1, -9, Material.AZALEA_LEAVES);
        tree(world, -14, Y + 1, 13, Material.FLOWERING_AZALEA_LEAVES);
        tree(world, 14, Y + 1, 14, Material.WARPED_WART_BLOCK);
        crystal(world, -20, Y + 2, 3);
        crystal(world, 20, Y + 2, -3);
    }

    private void path(World world, int fromX, int fromZ, int toX, int toZ, String title, String subtitle) {
        int dx = Integer.compare(toX, fromX);
        int dz = Integer.compare(toZ, fromZ);
        for (int i = 0; i <= 18; i++) {
            int x = fromX + dx * i;
            int z = fromZ + dz * i;
            for (int offset = -1; offset <= 1; offset++) {
                set(world, x + (dz == 0 ? 0 : offset), Y + 2, z + (dx == 0 ? 0 : offset), Material.POLISHED_DEEPSLATE);
            }
            if (i % 5 == 0) set(world, x, Y + 3, z, Material.SEA_LANTERN);
        }
        int px = toX - dx * 2;
        int pz = toZ - dz * 2;
        disk(world, px, Y + 2, pz, 4, Material.PURPUR_BLOCK);
        set(world, px, Y + 3, pz, Material.LODESTONE);
        Block signBlock = world.getBlockAt(px, Y + 3, pz - 1);
        signBlock.setType(Material.OAK_SIGN, false);
        if (signBlock.getState() instanceof Sign sign) {
            sign.setLine(0, "§b§l" + title);
            sign.setLine(1, "§7" + subtitle);
            sign.update();
        }
    }

    private void tree(World world, int x, int y, int z, Material leaves) {
        for (int trunk = 0; trunk < 6; trunk++) set(world, x, y + trunk, z, Material.DARK_OAK_LOG);
        for (int lx = -3; lx <= 3; lx++) for (int lz = -3; lz <= 3; lz++) for (int ly = 3; ly <= 7; ly++) {
            if (Math.abs(lx) + Math.abs(lz) + Math.abs(ly - 5) <= 5) set(world, x + lx, y + ly, z + lz, leaves);
        }
    }

    private void crystal(World world, int x, int y, int z) {
        for (int level = 0; level < 5; level++) {
            int radius = Math.max(0, 2 - level / 2);
            disk(world, x, y + level, z, radius, Material.AMETHYST_BLOCK);
        }
        set(world, x, y + 5, z, Material.SEA_LANTERN);
    }

    private void disk(World world, int x, int y, int z, int radius, Material material) {
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (dx * dx + dz * dz <= radius * radius) set(world, x + dx, y, z + dz, material);
        }
    }

    private void set(World world, int x, int y, int z, Material material) {
        world.getBlockAt(x, y, z).setType(material, false);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> player.teleportAsync(player.getWorld().getSpawnLocation()), 1L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!event.getPlayer().hasPermission("vectora.lobby.build")) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!event.getPlayer().hasPermission("vectora.lobby.build")) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player) event.setCancelled(true);
    }
}
