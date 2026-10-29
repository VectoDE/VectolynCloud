package net.vectora.cloud.bridge.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.vectora.cloud.bridge.KickCommandWatch;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.CommandMinecart;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Who may take a player off the network on Paper, and the reason that is reported. */
class KickSourceTest {

    /** A sender of the given type whose permission check answers {@code permitted}. */
    private static <T extends CommandSender> T sender(Class<T> type, boolean permitted) {
        Object fake = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "hasPermission", "isOp" -> permitted;
                    case "getName" -> "Steve";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> type.getSimpleName();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return type.cast(fake);
    }

    @Test
    @Tag("requires-java25") // loads paper-api 26.3 classes (class file version 69)
    void onlyTheConsoleRconAndPlayersWithThePermissionAreEntitled() {
        assertEquals(KickCommandWatch.Source.CONSOLE, PaperBridgePlugin.source(sender(ConsoleCommandSender.class, true)));
        assertEquals(KickCommandWatch.Source.REMOTE_CONSOLE,
                PaperBridgePlugin.source(sender(RemoteConsoleCommandSender.class, true)));
        assertTrue(PaperBridgePlugin.entitled(sender(ConsoleCommandSender.class, false)));
        assertTrue(PaperBridgePlugin.entitled(sender(RemoteConsoleCommandSender.class, false)));
        assertTrue(PaperBridgePlugin.entitled(sender(Player.class, true)));
        // Counter-checks: a normal player, command blocks and command minecarts.
        assertFalse(PaperBridgePlugin.entitled(sender(Player.class, false)));
        assertEquals(KickCommandWatch.Source.OTHER, PaperBridgePlugin.source(sender(BlockCommandSender.class, true)));
        assertFalse(PaperBridgePlugin.entitled(sender(BlockCommandSender.class, true)));
        assertFalse(PaperBridgePlugin.entitled(sender(CommandMinecart.class, true)));
    }

    @Test
    @Tag("requires-java25") // loads paper-api 26.3 classes (class file version 69)
    void theVanillaKickWithoutAReasonIsReportedEmpty() {
        assertEquals("", PaperBridgePlugin.plain(Component.translatable("multiplayer.disconnect.kicked")));
        assertEquals("Spam im Chat", PaperBridgePlugin.plain(Component.text("Spam im Chat")));
    }

    @Test
    void thePermissionGoesToOperatorsByDefault() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            Map<String, Object> yml = new Yaml().load(in);
            Map<?, ?> permissions = (Map<?, ?>) yml.get("permissions");
            Map<?, ?> kick = (Map<?, ?>) permissions.get(KickCommandWatch.PERMISSION);
            assertEquals("op", kick.get("default"));
        }
    }
}
