package net.vectora.cloud.bridge.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import net.kyori.adventure.text.Component;
import net.vectora.cloud.bridge.KickCommandWatch;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.CommandMinecart;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Who may take a player off the network on Paper, and the reason that is reported. No method
 * signature names a paper-api type (class file version 69): JUnit reads the signatures during
 * discovery, also on Java 21, where the tagged tests are then skipped.
 */
class KickSourceTest {

    /** A sender of the given type whose permission check answers {@code permitted}. */
    private static Object sender(Class<?> type, boolean permitted) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "hasPermission", "isOp" -> permitted;
                    case "getName" -> "Steve";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> type.getSimpleName();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static KickCommandWatch.Source source(Class<?> type) {
        return PaperBridgePlugin.source((CommandSender) sender(type, true));
    }

    private static boolean entitled(Class<?> type, boolean permitted) {
        return PaperBridgePlugin.entitled((CommandSender) sender(type, permitted));
    }

    @Test
    @Tag("requires-java25") // loads paper-api 26.3 classes (class file version 69)
    void onlyTheConsoleRconAndPlayersWithThePermissionAreEntitled() {
        assertEquals(KickCommandWatch.Source.CONSOLE, source(ConsoleCommandSender.class));
        assertEquals(KickCommandWatch.Source.REMOTE_CONSOLE, source(RemoteConsoleCommandSender.class));
        assertTrue(entitled(ConsoleCommandSender.class, false));
        assertTrue(entitled(RemoteConsoleCommandSender.class, false));
        assertTrue(entitled(Player.class, true));
        // Counter-checks: a normal player, command blocks and command minecarts.
        assertFalse(entitled(Player.class, false));
        assertEquals(KickCommandWatch.Source.OTHER, source(BlockCommandSender.class));
        assertFalse(entitled(BlockCommandSender.class, true));
        assertFalse(entitled(CommandMinecart.class, true));
    }

    /** A proxied sender (a plugin's {@code execute as}) whose caller is a sender of the given type. */
    private static boolean entitledThroughProxy(Class<?> callerType, boolean permitted) {
        Object caller = sender(callerType, permitted);
        Object proxied = Proxy.newProxyInstance(ProxiedCommandSender.class.getClassLoader(),
                new Class<?>[] {ProxiedCommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getCaller" -> caller;
                    case "hasPermission", "isOp" -> true;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return PaperBridgePlugin.entitled((CommandSender) proxied);
    }

    @Test
    @Tag("requires-java25") // loads paper-api 26.3 classes (class file version 69)
    void aProxiedSenderCountsAsItsCaller() {
        assertTrue(entitledThroughProxy(ConsoleCommandSender.class, false));
        assertFalse(entitledThroughProxy(Player.class, false), "a normal player behind an operator's proxy");
    }

    @Test
    @Tag("requires-java25") // loads paper-api 26.3 classes (class file version 69)
    void theVanillaKickWithoutAReasonIsReportedEmpty() {
        assertEquals("", PaperBridgePlugin.plain(Component.translatable("multiplayer.disconnect.kicked")));
        assertEquals("Spam im Chat", PaperBridgePlugin.plain(Component.text("Spam im Chat")));
    }
}
