package net.vectora.cloud.bridge.legacy;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import java.util.Optional;
import net.vectora.cloud.bridge.BridgeConnection;
import net.vectora.cloud.bridge.BridgeSender;
import net.vectora.cloud.bridge.StatusSnapshot;
import net.vectora.cloud.bridge.TickMeter;
import net.vectora.cloud.client.CloudClientSettings;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VectoraCloud bridge for PandaSpigot 1.8.8. Thin adapter: the token check lives in
 * {@link HandshakeGuard} and {@code BungeeGuardCheck}, the cloud calls go through
 * {@link BridgeSender}, never from the server thread.
 *
 * <ul>
 *   <li>The token check is enabled first and independently of the cloud connection.</li>
 *   <li>Ready: the first server tick, which comes after "Done".</li>
 *   <li>Status: every {@value #STATUS_PERIOD_TICKS} ticks, and at once when a player joins or
 *       leaves. TPS and MSPT come from {@code ServerTickEndEvent}, as 1.8.8 has no TPS API.</li>
 * </ul>
 *
 * <p>Network-wide player events ({@code PlayerJoin} and so on) are reported by the proxy only, so
 * the cloud gets each of them once.
 */
public final class LegacyBridgePlugin extends JavaPlugin implements Listener {

    /** Status period: five seconds at full speed. */
    public static final long STATUS_PERIOD_TICKS = 100;

    private final TickMeter ticks = new TickMeter();
    private BridgeSender sender;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(HandshakeGuard.fromEnvironment(System::getenv, getLogger()), this);
        Optional<CloudClientSettings> settings = BridgeConnection.settings();
        if (!settings.isPresent()) {
            return;
        }
        sender = BridgeConnection.open(settings.get(), created -> null);
        getServer().getPluginManager().registerEvents(this, this);
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

    /** Feeds the tick meter. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        ticks.tick(System.nanoTime(), event.getTickDuration());
    }

    private void report(int adjust) {
        BridgeSender target = sender;
        if (target == null) {
            return;
        }
        int online = Math.max(0, Bukkit.getOnlinePlayers().size() + adjust);
        target.reportStatus(new StatusSnapshot(online, Bukkit.getMaxPlayers(), ticks.tps(), ticks.mspt()));
    }
}
