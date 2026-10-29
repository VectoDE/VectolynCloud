package net.vectora.cloud.bridge.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The bridge's {@code kick} node in a plain Brigadier dispatcher shaped like the server's: it takes
 * the place of the vanilla {@code kick} and {@code minecraft:kick}, {@code execute ... run kick}
 * and every dispatch reach it, and an unentitled source cannot use it. Brigadier only, so it runs
 * on Java 21 as well.
 */
class KickCommandNodeTest {

    /** A command source: whether it is entitled to take players off the network. */
    private record Source(boolean entitled) {
    }

    private static final Source ENTITLED = new Source(true);
    private static final Source PLAYER = new Source(false);

    private final List<String> calls = new ArrayList<>();
    private CommandDispatcher<Source> dispatcher;

    @BeforeEach
    void vanillaCommands() {
        dispatcher = new CommandDispatcher<>();
        for (String literal : List.of("kick", "minecraft:kick")) {
            dispatcher.register(LiteralArgumentBuilder.<Source>literal(literal)
                    .requires(source -> true)
                    .then(RequiredArgumentBuilder.<Source, String>argument(KickCommandNode.TARGETS,
                                    StringArgumentType.word())
                            .executes(context -> {
                                calls.add("vanilla " + StringArgumentType.getString(context, KickCommandNode.TARGETS));
                                return 1;
                            })));
        }
        // Vanilla's execute: "run" hands the rest of the line back to the root.
        dispatcher.register(LiteralArgumentBuilder.<Source>literal("execute")
                .then(LiteralArgumentBuilder.<Source>literal("run").redirect(dispatcher.getRoot())));
    }

    private LiteralCommandNode<Source> node(String literal) {
        return KickCommandNode.build(literal, Source::entitled, StringArgumentType.word(), (context, reason) -> {
            calls.add("network " + StringArgumentType.getString(context, KickCommandNode.TARGETS) + "|" + reason);
            return 1;
        });
    }

    private void replaceBoth() {
        KickCommandNode.replace(dispatcher.getRoot(), node("kick"));
        KickCommandNode.replace(dispatcher.getRoot(), node("minecraft:kick"));
    }

    @Test
    void itTakesThePlaceOfBothVanillaLabels() throws CommandSyntaxException {
        replaceBoth();

        dispatcher.execute("kick Bob", ENTITLED);
        dispatcher.execute("minecraft:kick Bob Spam im Chat", ENTITLED);

        assertEquals(List.of("network Bob|", "network Bob|Spam im Chat"), calls);
    }

    @Test
    void executeRunKickReachesIt() throws CommandSyntaxException {
        replaceBoth();

        dispatcher.execute("execute run kick Bob weg", ENTITLED);
        dispatcher.execute("execute run minecraft:kick Bob", ENTITLED);

        assertEquals(List.of("network Bob|weg", "network Bob|"), calls);
    }

    @Test
    void anUnentitledSourceCannotUseIt() {
        replaceBoth();

        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("kick Bob", PLAYER));
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("minecraft:kick Bob", PLAYER));
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("execute run kick Bob", PLAYER));
        assertTrue(calls.isEmpty(), "neither a network kick nor the vanilla kick");
    }

    @Test
    void withoutRemovingFirstBrigadierWouldKeepTheVanillaRequirement() throws CommandSyntaxException {
        // Why replace() removes first: addChild merges a node of the same name into the old one.
        dispatcher.getRoot().addChild(node("kick"));

        dispatcher.execute("kick Bob", PLAYER);

        assertEquals(List.of("network Bob|"), calls, "the old requirement stayed: a normal player got through");
    }

    @Test
    void aMissingLabelIsAdded() throws CommandSyntaxException {
        CommandDispatcher<Source> empty = new CommandDispatcher<>();
        KickCommandNode.replace(empty.getRoot(), node("kick"));

        empty.execute("kick Bob", ENTITLED);

        assertEquals(List.of("network Bob|"), calls);
    }
}
