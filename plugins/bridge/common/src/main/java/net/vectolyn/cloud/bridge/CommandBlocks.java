package net.vectolyn.cloud.bridge;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The network-wide rule for command blocks, without any platform type: a command block gives
 * whoever reaches one the power of the console, so the backends remove them completely. The cloud
 * sets {@value #PROPERTY}{@code =false} in every rendered {@code server.properties}; the bridges
 * keep the items out of every inventory, refuse placing and using them, and remove the blocks and
 * minecarts they find when a chunk or its entities load. There is no setting to turn this off.
 *
 * <p>This class knows the material names of both server APIs (Paper 26.3 and PandaSpigot 1.8.8)
 * and writes the log lines. Removals are logged with world and position; at most
 * {@value #LOG_LINES} lines per {@value #LOG_SECONDS} seconds get through, so a world full of
 * command blocks cannot flood the log, and the next line that gets through tells how many were
 * left out. Player names are never logged.
 */
public final class CommandBlocks {

    /** The key in {@code server.properties} that the cloud always sets to {@code false}. */
    public static final String PROPERTY = "enable-command-block";

    /** Log lines per interval. */
    public static final int LOG_LINES = 20;
    /** The log interval in seconds. */
    public static final int LOG_SECONDS = 10;

    /**
     * Material names of command blocks and command block minecarts: Paper 26.3 (with its legacy
     * names) and Bukkit 1.8.8 ({@code COMMAND}, {@code COMMAND_MINECART}).
     */
    private static final Set<String> MATERIALS = Set.of(
            "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK", "COMMAND_BLOCK_MINECART",
            "LEGACY_COMMAND", "LEGACY_COMMAND_CHAIN", "LEGACY_COMMAND_REPEATING", "LEGACY_COMMAND_MINECART",
            "COMMAND", "COMMAND_MINECART");

    private final Consumer<String> log;
    private final LogThrottle throttle;

    /**
     * Creates the log for removals.
     *
     * @param log takes each line that gets through (the plugin's logger at INFO)
     */
    public CommandBlocks(Consumer<String> log) {
        this(log, System::nanoTime);
    }

    /** As above with a clock of its own, for tests. */
    CommandBlocks(Consumer<String> log, LongSupplier clock) {
        this.log = Objects.requireNonNull(log, "log");
        this.throttle = new LogThrottle(LOG_LINES, TimeUnit.SECONDS.toNanos(LOG_SECONDS), clock);
    }

    /**
     * Whether a material is a command block of any kind or a command block minecart.
     *
     * @param material the name of the platform's material, {@code Material.name()}; may be null
     * @return whether it must not exist on the network
     */
    public static boolean forbidden(String material) {
        return material != null && MATERIALS.contains(material.toUpperCase(Locale.ROOT));
    }

    /**
     * Logs a command block that was removed from the world.
     *
     * @param world the world's name
     * @param x     block x
     * @param y     block y
     * @param z     block z
     * @param why   when it was found, for example {@code "on chunk load"}
     */
    public void blockRemoved(String world, int x, int y, int z, String why) {
        line("Removed a command block in world " + world + " at " + x + ", " + y + ", " + z + " (" + why + ")");
    }

    /**
     * Logs a command block minecart that was removed.
     *
     * @param world the world's name
     * @param x     block x of the minecart
     * @param y     block y of the minecart
     * @param z     block z of the minecart
     * @param why   when it was found, for example {@code "on entity load"}
     */
    public void minecartRemoved(String world, int x, int y, int z, String why) {
        line("Removed a command block minecart in world " + world + " at " + x + ", " + y + ", " + z + " (" + why + ")");
    }

    /**
     * Logs command block items that were taken away.
     *
     * @param count how many stacks
     * @param where where they were, for example {@code "a player's inventory in world lobby"}
     */
    public void itemsRemoved(int count, String where) {
        if (count > 0) {
            line("Removed " + count + (count == 1 ? " command block item from " : " command block items from ")
                    + where);
        }
    }

    private void line(String text) {
        long dropped = throttle.acquire();
        if (dropped < 0) {
            return;
        }
        log.accept(dropped == 0 ? text : text + " (" + dropped + " more removals not logged)");
    }
}
