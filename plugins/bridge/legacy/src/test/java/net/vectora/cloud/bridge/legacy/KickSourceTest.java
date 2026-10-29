package net.vectora.cloud.bridge.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.Map;
import net.vectora.cloud.bridge.KickCommandWatch;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.CommandMinecart;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Who may take a player off the network on 1.8.8: the console, RCON and entitled players only. */
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
    void theConsoleRconAndPlayersWithThePermissionAreEntitled() {
        assertEquals(KickCommandWatch.Source.CONSOLE, LegacyBridgePlugin.source(sender(ConsoleCommandSender.class, true)));
        assertEquals(KickCommandWatch.Source.REMOTE_CONSOLE,
                LegacyBridgePlugin.source(sender(RemoteConsoleCommandSender.class, true)));
        assertTrue(LegacyBridgePlugin.entitled(sender(ConsoleCommandSender.class, false)));
        assertTrue(LegacyBridgePlugin.entitled(sender(RemoteConsoleCommandSender.class, false)));
        assertTrue(LegacyBridgePlugin.entitled(sender(Player.class, true)));
    }

    @Test
    void playersWithoutThePermissionAndCommandBlocksAreNot() {
        assertFalse(LegacyBridgePlugin.entitled(sender(Player.class, false)), "a normal player");
        assertEquals(KickCommandWatch.Source.OTHER, LegacyBridgePlugin.source(sender(BlockCommandSender.class, true)));
        assertFalse(LegacyBridgePlugin.entitled(sender(BlockCommandSender.class, true)), "a command block");
        assertFalse(LegacyBridgePlugin.entitled(sender(CommandMinecart.class, true)), "a command minecart");
    }

    @Test
    void theKickReasonIsReadAsPlainTextThroughAdventure() {
        assertEquals("Spam im Chat", LegacyBridgePlugin.plain(
                net.kyori.adventure.text.Component.text("Spam ").append(net.kyori.adventure.text.Component.text("im Chat"))));
        assertEquals("", KickCommandWatch.reason(LegacyBridgePlugin.plain(
                net.kyori.adventure.text.Component.text("Kicked by an operator."))), "the vanilla text reports empty");
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
