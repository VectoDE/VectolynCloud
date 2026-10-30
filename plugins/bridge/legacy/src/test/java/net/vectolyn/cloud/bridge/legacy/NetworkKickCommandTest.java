package net.vectolyn.cloud.bridge.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * The bridge's {@code kick} on 1.8.8: every way a command reaches the command map ends in a network
 * kick, and only for an entitled sender.
 */
class NetworkKickCommandTest {

    private final List<String> kicks = new ArrayList<>();
    private final List<String> selectorLines = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private final Player bob = player("Bob", true);

    private final NetworkKickCommand command = new NetworkKickCommand(
            name -> name.equalsIgnoreCase("bob") ? bob : null,
            (issuer, player, reason) -> {
                if (!LegacyBridgePlugin.entitled(issuer)) {
                    return false;
                }
                kicks.add(player.getName() + "|" + reason);
                return true;
            },
            (issuer, label, args) -> selectorLines.add(label + " " + String.join(" ", args)));

    /** A sender of the given type; its permission check answers {@code permitted}, messages are kept. */
    private <T extends CommandSender> T sender(Class<T> type, boolean permitted) {
        Object fake = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "hasPermission", "isOp" -> permitted;
                    case "getName" -> type.getSimpleName();
                    case "sendMessage" -> {
                        messages.add(String.valueOf(args[0]));
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> type.getSimpleName();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return type.cast(fake);
    }

    private static Player player(String name, boolean permitted) {
        UUID id = UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Object fake = Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission", "isOp" -> permitted;
                    case "getName" -> name;
                    case "getUniqueId" -> id;
                    case "sendMessage" -> null;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (Player) fake;
    }

    /** {@code /execute} on 1.8.8 runs the command with a proxied sender whose caller started it. */
    private ProxiedCommandSender proxied(CommandSender caller) {
        Object fake = Proxy.newProxyInstance(ProxiedCommandSender.class.getClassLoader(),
                new Class<?>[] {ProxiedCommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getCaller" -> caller;
                    case "getCallee" -> bob;
                    case "hasPermission", "isOp" -> true;
                    case "getName" -> "@";
                    case "sendMessage" -> {
                        messages.add(String.valueOf(args[0]));
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "proxied";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (ProxiedCommandSender) fake;
    }

    @Test
    void theConsoleKicksANamedPlayerOffTheNetworkWithTheReason() {
        command.execute(sender(ConsoleCommandSender.class, true), "kick", new String[] {"bob", "Spam", "im", "Chat"});
        command.execute(sender(RemoteConsoleCommandSender.class, true), "minecraft:kick", new String[] {"Bob"});

        assertEquals(List.of("Bob|Spam im Chat", "Bob|"), kicks);
        assertEquals("Kicked Bob from the game: 'Spam im Chat'", messages.get(0));
    }

    @Test
    void aPlayerWithThePermissionMayKick() {
        command.execute(player("Mod", true), "kick", new String[] {"Bob"});

        assertEquals(List.of("Bob|"), kicks);
    }

    @Test
    void anUnentitledSenderTriggersNoNetworkKick() {
        command.execute(player("Steve", false), "kick", new String[] {"Bob", "weg"});
        command.execute(sender(BlockCommandSender.class, true), "kick", new String[] {"Bob"});
        command.execute(proxied(player("Steve", false)), "kick", new String[] {"Bob"});
        command.execute(player("Steve", false), "kick", new String[] {"@a"});

        assertTrue(kicks.isEmpty(), "no network kick");
        assertTrue(selectorLines.isEmpty(), "no selector handed on");
    }

    @Test
    void executeRunsItWithAProxiedSenderThatCountsAsItsCaller() {
        command.execute(proxied(sender(ConsoleCommandSender.class, true)), "kick", new String[] {"Bob", "weg"});

        assertEquals(List.of("Bob|weg"), kicks);
    }

    @Test
    void selectorsGoToTheServersCommandWhileTheKicksAreWatched() {
        command.execute(sender(ConsoleCommandSender.class, true), "kick", new String[] {"@a", "Wartung"});

        assertEquals(List.of("kick @a Wartung"), selectorLines);
        assertTrue(kicks.isEmpty());
    }

    @Test
    void anUnknownPlayerOrAMissingTargetKicksNobody() {
        command.execute(sender(ConsoleCommandSender.class, true), "kick", new String[] {"Alex"});
        command.execute(sender(ConsoleCommandSender.class, true), "kick", new String[0]);

        assertTrue(kicks.isEmpty());
        assertEquals("§cThat player cannot be found", messages.get(0));
    }

    @Test
    void itTakesTheVanillaLabelsAndKeepsTheVanillaCommandForSelectors() {
        Command vanilla = new Command("kick") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return true;
            }
        };
        Map<String, Command> known = new HashMap<>();
        known.put("kick", vanilla);
        known.put("minecraft:kick", vanilla);

        assertSame(vanilla, NetworkKickCommand.install(known, command));
        assertSame(command, known.get("kick"));
        assertSame(command, known.get("minecraft:kick"));
        // A second run (after /reload) keeps the vanilla command it found before.
        known.put("minecraft:kick", vanilla);
        assertSame(vanilla, NetworkKickCommand.install(known, command));
    }

    @Test
    void dispatchCommandReachesItThroughTheCommandMap() {
        // CraftServer.dispatchCommand, Player.performCommand, the aliases of commands.yml and
        // /execute all end in SimpleCommandMap.dispatch, which runs knownCommands.get(label). A real
        // SimpleCommandMap needs the server's timings libraries (Trove), so the test dispatches like
        // it on the same map.
        Command vanilla = new Command("kick") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                kicks.add("vanilla");
                return true;
            }
        };
        Map<String, Command> known = new HashMap<>();
        known.put("kick", vanilla);
        known.put("minecraft:kick", vanilla);
        known.put("vectolyncloud:kick", command);
        NetworkKickCommand.install(known, command);

        ConsoleCommandSender console = sender(ConsoleCommandSender.class, true);
        for (String line : List.of("kick Bob Spam", "minecraft:kick Bob", "vectolyncloud:kick Bob", "KICK Bob")) {
            String[] parts = line.split(" ");
            Command target = known.get(parts[0].toLowerCase(java.util.Locale.ROOT));
            assertTrue(target.execute(console, parts[0], java.util.Arrays.copyOfRange(parts, 1, parts.length)));
        }

        assertEquals(List.of("Bob|Spam", "Bob|", "Bob|", "Bob|"), kicks);
    }

    @Test
    void theCommandMapOf188StillKeepsItsLabelsWhereTheBridgeLooks() throws NoSuchFieldException {
        assertEquals(Map.class, SimpleCommandMap.class.getDeclaredField("knownCommands").getType());
    }

    @Test
    void theReasonIsTheRestOfTheLine() {
        assertEquals("", NetworkKickCommand.reason(new String[] {"Bob"}));
        assertEquals("zu laut", NetworkKickCommand.reason(new String[] {"Bob", "zu", "laut"}));
    }
}
