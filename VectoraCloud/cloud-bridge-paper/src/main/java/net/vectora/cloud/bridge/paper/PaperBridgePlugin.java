package net.vectora.cloud.bridge.paper;

import java.util.Optional;
import net.vectora.cloud.bridge.BridgeConnection;
import net.vectora.cloud.bridge.BridgeSender;
import net.vectora.cloud.bridge.StatusSnapshot;
import net.vectora.cloud.client.CloudClientSettings;
import org.bukkit.Bukkit;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VectoraCloud bridge for Paper 26.3. Thin adapter over cloud-bridge-common: every call to the
 * cloud goes through {@link BridgeSender}, the main thread only takes a snapshot and queues it.
 *
 * <ul>
 *   <li>Ready: the first server tick, which comes after "Done".</li>
 *   <li>Status: every {@value #STATUS_PERIOD_TICKS} ticks with Paper's TPS and MSPT, and at once
 *       when a player joins or leaves.</li>
 * </ul>
 *
 * <p>Network-wide player events ({@code PlayerJoin} and so on) are reported by the proxy only, so
 * the cloud gets each of them once.
 */
public final class PaperBridgePlugin extends JavaPlugin implements Listener {

    /** Status period: five seconds at full speed. */
    public static final long STATUS_PERIOD_TICKS = 100;

    private BridgeSender sender;

    @Override
    public void onEnable() {
        Optional<CloudClientSettings> settings = BridgeConnection.settings();
        if (settings.isEmpty()) {
            getLogger().info("Not started by VectoraCloud (no connection settings): the bridge stays idle");
            return;
        }
        getLogger().info("VectoraCloud bridge runs as a backend behind the main proxy (modern forwarding)");
        sender = BridgeConnection.open(settings.get(), created -> null);
        // The player count changes: report at once. The leaving player is still counted during the
        // quit event. Registered as executors, the handlers need nothing from the event.
        getServer().getPluginManager().registerEvent(PlayerJoinEvent.class, this, EventPriority.MONITOR,
                (listener, event) -> report(0), this, false);
        getServer().getPluginManager().registerEvent(PlayerQuitEvent.class, this, EventPriority.MONITOR,
                (listener, event) -> report(-1), this, false);
        BridgeSender ready = sender;
        getServer().getScheduler().runTask(this, ready::markReady);
        getServer().getScheduler().runTaskTimer(this, () -> report(0), STATUS_PERIOD_TICKS, STATUS_PERIOD_TICKS);
    }

    @Override
    public void onDisable() {
        if (sender != null) {
            sender.close();
            sender = null;
        }
    }

    private void report(int adjust) {
        BridgeSender target = sender;
        if (target == null) {
            return;
        }
        int online = Math.max(0, Bukkit.getOnlinePlayers().size() + adjust);
        target.reportStatus(new StatusSnapshot(online, Bukkit.getMaxPlayers(), Bukkit.getTPS()[0],
                Bukkit.getAverageTickTime()));
    }
}
