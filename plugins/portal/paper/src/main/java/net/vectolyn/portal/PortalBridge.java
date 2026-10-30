package net.vectolyn.portal;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Sends players entering an End portal to the dedicated End backend via Velocity. */
public final class PortalBridge extends JavaPlugin implements Listener {
    private static final String PROXY_CHANNEL = "BungeeCord";
    private static final String END_SERVER = "end";

    @Override
    public void onEnable() {
        getServer().getMessenger().registerOutgoingPluginChannel(this, PROXY_CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Vectolyn End portal bridge enabled; End portals route to the 'end' backend.");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, PROXY_CHANNEL);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEndPortal(PlayerPortalEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        try {
            player.sendPluginMessage(this, PROXY_CHANNEL, connectPayload(END_SERVER));
        } catch (IOException exception) {
            getLogger().warning("Could not route " + player.getName() + " to the End server: " + exception.getMessage());
            player.sendMessage("§cThe End server is currently unavailable.");
        }
    }

    /** Builds the BungeeCord-channel "Connect" message that Velocity understands. */
    static byte[] connectPayload(String server) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("Connect");
            out.writeUTF(server);
            out.flush();
            return bytes.toByteArray();
        }
    }
}
