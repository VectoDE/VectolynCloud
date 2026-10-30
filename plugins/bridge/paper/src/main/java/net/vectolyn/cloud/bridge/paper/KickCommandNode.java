package net.vectolyn.cloud.bridge.paper;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The bridge's {@code kick} command as a Brigadier node, without a Paper type, so it can be
 * tested on Java 21 with a plain dispatcher.
 *
 * <p>The node has the shape of the vanilla command: {@code kick <targets> [<reason>]}, the reason
 * taking the rest of the line. Replacing the node in the dispatcher's root ({@link #replace}) also
 * takes over {@code execute ... run kick} and every {@code dispatchCommand}, because both resolve the
 * command from the root.
 */
final class KickCommandNode {

    /** Name of the targets argument, as in vanilla. */
    static final String TARGETS = "targets";
    /** Name of the reason argument, as in vanilla. */
    static final String REASON = "reason";

    private KickCommandNode() {
    }

    /** What the command does with a parsed line. */
    @FunctionalInterface
    interface Action<S> {
        /**
         * Runs the kick.
         *
         * @param context the parsed command; the targets are the argument {@link KickCommandNode#TARGETS}
         * @param reason  the reason, empty without one
         * @return the number of kicked players
         * @throws CommandSyntaxException if the targets cannot be resolved
         */
        int run(CommandContext<S> context, String reason) throws CommandSyntaxException;
    }

    /**
     * Builds the node.
     *
     * @param literal     {@code kick} or {@code minecraft:kick}
     * @param requirement who may use it
     * @param targets     the argument type of the targets (Paper: players and selectors)
     * @param action      the kick
     * @param <S>         the command source
     * @param <T>         the parsed targets
     * @return the node
     */
    static <S, T> LiteralCommandNode<S> build(String literal, Predicate<S> requirement, ArgumentType<T> targets,
                                              Action<S> action) {
        Objects.requireNonNull(action, "action");
        Command<S> withoutReason = context -> action.run(context, "");
        Command<S> withReason = context -> action.run(context, StringArgumentType.getString(context, REASON).strip());
        return LiteralArgumentBuilder.<S>literal(literal)
                .requires(requirement)
                .then(RequiredArgumentBuilder.<S, T>argument(TARGETS, targets)
                        .executes(withoutReason)
                        .then(RequiredArgumentBuilder.<S, String>argument(REASON, StringArgumentType.greedyString())
                                .executes(withReason)))
                .build();
    }

    /**
     * Puts the bridge's node in place of {@code kick} (unless the regular registration already took
     * that name) and of {@code minecraft:kick} (if the server has it). Any failure of the server's
     * Brigadier is caught: the caller warns and keeps the command line watch as the fallback.
     *
     * @param root           the dispatcher's root
     * @param kickRegistered whether the regular registration got {@code kick}
     * @param nodes          builds the node for a literal
     * @param warn           gets the failure
     * @param <S>            the command source
     * @return whether both names now run the bridge's node
     */
    static <S> boolean install(CommandNode<S> root, boolean kickRegistered,
                               Function<String, LiteralCommandNode<S>> nodes, Consumer<RuntimeException> warn) {
        try {
            if (!kickRegistered) {
                replace(root, nodes.apply("kick"));
            }
            if (root.getChild("minecraft:kick") != null) {
                replace(root, nodes.apply("minecraft:kick"));
            }
            return true;
        } catch (RuntimeException e) {
            warn.accept(e);
            return false;
        }
    }

    /**
     * Puts a node in place of the root's child of the same name. Brigadier would merge a second
     * node of that name into the first and keep the old requirement, so the old one goes first:
     * through {@code removeCommand} if the server's Brigadier has it, otherwise through the maps
     * of {@link CommandNode}.
     *
     * @param root the dispatcher's root
     * @param node the new node
     * @param <S>  the command source
     * @throws IllegalStateException if the old node cannot be removed
     */
    static <S> void replace(CommandNode<S> root, LiteralCommandNode<S> node) {
        if (root.getChild(node.getName()) != null) {
            remove(root, node.getName());
        }
        root.addChild(node);
    }

    private static void remove(CommandNode<?> root, String name) {
        try {
            Method method = root.getClass().getMethod("removeCommand", String.class);
            method.invoke(root, name);
            return;
        } catch (NoSuchMethodException absent) {
            // Mojang's Brigadier has no removeCommand: clear the maps below.
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Cannot remove the command " + name, e);
        }
        boolean removed = false;
        for (String mapName : new String[] {"children", "literals", "arguments"}) {
            try {
                Field field = CommandNode.class.getDeclaredField(mapName);
                field.setAccessible(true);
                Map<?, ?> map = (Map<?, ?>) field.get(root);
                removed |= map.remove(name) != null;
            } catch (NoSuchFieldException absent) {
                // Not every map exists in every Brigadier build; "children" decides.
            } catch (IllegalAccessException | RuntimeException e) {
                throw new IllegalStateException("Cannot remove the command " + name, e);
            }
        }
        if (!removed || root.getChild(name) != null) {
            throw new IllegalStateException("Cannot remove the command " + name);
        }
    }
}
