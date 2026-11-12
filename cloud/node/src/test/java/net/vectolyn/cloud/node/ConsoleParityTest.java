// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import net.vectolyn.cloud.api.http.ApiRoutes;
import net.vectolyn.cloud.config.model.Language;
import net.vectolyn.cloud.console.Console;
import net.vectolyn.cloud.console.command.Argument;
import net.vectolyn.cloud.console.command.Command;
import net.vectolyn.cloud.console.command.CommandDispatcher;
import net.vectolyn.cloud.console.command.CommandDispatcher.Outcome;
import net.vectolyn.cloud.console.command.CommandRegistry;
import net.vectolyn.cloud.console.terminal.PlainTerminal;
import net.vectolyn.cloud.node.service.ServiceState;
import net.vectolyn.protocol.ServiceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The console of a whole node: every endpoint of the REST API has a console command
 * (docs/cloud/console.md#console-and-rest-api), every command explains itself in both languages,
 * and tab completion knows services, groups, tasks and nodes.
 */
class ConsoleParityTest {

    /**
     * Each endpoint ({@code METHOD path}) and the console commands that do the same. A new endpoint
     * fails {@link #everyEndpointHasAConsoleCommand} until it gets a line here and in console.md.
     */
    static final Map<String, List<String>> PARITY = new TreeMap<>(Map.ofEntries(
            Map.entry("GET /api/v1/services", List.of("service list")),
            Map.entry("POST /api/v1/services", List.of("service start")),
            Map.entry("GET /api/v1/services/{id}", List.of("service info")),
            Map.entry("POST /api/v1/services/{id}/stop", List.of("service stop")),
            Map.entry("POST /api/v1/services/{id}/restart", List.of("service restart")),
            Map.entry("POST /api/v1/services/{id}/command", List.of("service command")),
            Map.entry("GET /api/v1/groups", List.of("group list")),
            Map.entry("GET /api/v1/groups/{name}", List.of("group info")),
            Map.entry("PUT /api/v1/groups/{name}", List.of("group create", "group set")),
            Map.entry("DELETE /api/v1/groups/{name}", List.of("group delete")),
            Map.entry("PUT /api/v1/groups/{name}/wanted", List.of("group set")),
            Map.entry("GET /api/v1/tasks", List.of("task list")),
            Map.entry("GET /api/v1/tasks/{name}", List.of("task info")),
            Map.entry("PUT /api/v1/tasks/{name}", List.of("task create", "task set")),
            Map.entry("DELETE /api/v1/tasks/{name}", List.of("task delete")),
            Map.entry("PUT /api/v1/tasks/{name}/wanted", List.of("group set")),
            Map.entry("GET /api/v1/nodes", List.of("node list")),
            Map.entry("GET /api/v1/nodes/{name}", List.of("node info")),
            Map.entry("PUT /api/v1/nodes/{name}", List.of("node create", "node set")),
            Map.entry("GET /api/v1/servers", List.of("config get", "config list")),
            Map.entry("PUT /api/v1/servers/{kind}", List.of("config set")),
            Map.entry("GET /api/v1/config", List.of("config list", "config get")),
            Map.entry("PUT /api/v1/config", List.of("config set")),
            Map.entry("POST /api/v1/config/reload", List.of("config reload")),
            Map.entry("GET /api/v1/config/warnings", List.of("config warnings")),
            Map.entry("GET /api/v1/node", List.of("node status")),
            Map.entry("GET /api/v1/setup", List.of("config get", "setup")),
            Map.entry("GET /api/v1/backups", List.of("backup list")),
            Map.entry("POST /api/v1/backups", List.of("backup create")),
            // The token asks about itself; on the console the operator sees every token.
            Map.entry("GET /api/v1/tokens/self", List.of("api token list")),
            // The event stream; on the console the log lines and "service screen" show the same live.
            Map.entry("GET /api/v1/events", List.of("service screen"))));

    @TempDir
    Path dir;

    @Test
    void everyEndpointHasAConsoleCommand() throws Exception {
        NodeFixture fixture = NodeFixture.create(dir, true, List.of());
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        Console console = Console.builder().terminal(new PlainTerminal(new ByteArrayInputStream(new byte[0]),
                new PrintStream(screen, true, StandardCharsets.UTF_8))).build();
        CloudNode node = CloudNode.open(fixture.settings(), fixture.options().andThen(o -> o.console(console)));
        try {
            node.start();
            CommandRegistry registry = console.commands();

            TreeSet<String> endpoints = new TreeSet<>();
            new ApiRoutes(node.config(), node.apiBackend(), () -> false).router().routes()
                    .forEach(route -> endpoints.add(route.method() + " " + route.path()));
            assertEquals(endpoints, new TreeSet<>(PARITY.keySet()), "every endpoint is mapped, and only those");
            PARITY.forEach((endpoint, lines) -> lines.forEach(line ->
                    assertTrue(resolve(registry, line).isPresent(), endpoint + " -> " + line)));

            // The new read commands work on a running node.
            assertEquals(Outcome.DONE, console.dispatcher().execute("node status"));
            assertEquals(Outcome.DONE, console.dispatcher().execute("config warnings"));
            String text = screen.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains("Java:") && text.contains("Uptime:") && text.contains("Heap:"), text);
            assertTrue(text.contains("groups/lobby.yml: process.jvm-flags[0]"), "the fixture's -Dfake.cloud flag: " + text);
        } finally {
            node.close();
        }
    }

    @Test
    void everyCommandOfTheNodeHasHelpAndCompletionKnowsTheRunningServices() throws Exception {
        NodeFixture fixture = NodeFixture.create(dir, true, List.of());
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        Console console = Console.builder().terminal(new PlainTerminal(new ByteArrayInputStream(new byte[0]),
                new PrintStream(screen, true, StandardCharsets.UTF_8))).build();
        CloudNode node = CloudNode.open(fixture.settings(), fixture.options().andThen(o -> o.console(console)));
        try {
            node.start();
            CommandRegistry registry = console.commands();
            int checked = 0;
            for (Command command : registry.commands()) {
                checked += checkHelp(command);
            }
            assertTrue(checked >= 60, "the whole tree of the node: " + checked);

            NodeFixture.await("lobby-1 running",
                    () -> NodeEndToEndTest.state(node, new ServiceId("lobby-1")) == ServiceState.RUNNING);
            assertEquals(List.of("lobby-1"), CommandDispatcher.complete(registry, "service stop "));
            assertEquals(List.of("lobby-1"), CommandDispatcher.complete(registry, "services info lob"));
            assertEquals(List.of("lobby"), CommandDispatcher.complete(registry, "service start "));
            assertEquals(List.of("lobby"), CommandDispatcher.complete(registry, "group info "));
            assertEquals(List.of("lobby"), CommandDispatcher.complete(registry, "task set "));
            assertEquals(List.of("node-1"), CommandDispatcher.complete(registry, "node info "));
            assertTrue(CommandDispatcher.complete(registry, "node ").contains("status"));
            assertEquals(List.of("alerts", "api"), CommandDispatcher.complete(registry, "a"));
            assertEquals(List.of("cluster"), CommandDispatcher.complete(registry, "clu"));
            assertTrue(CommandDispatcher.complete(registry, "s").containsAll(
                    List.of("service", "services", "setup", "shutdown")), CommandDispatcher.complete(registry, "s").toString());
        } finally {
            node.close();
        }
    }

    /** The command a line like {@code group set} names, following subcommands. */
    static Optional<Command> resolve(CommandRegistry registry, String line) {
        String[] words = line.split(" ");
        Optional<Command> current = registry.find(words[0]);
        for (int i = 1; i < words.length && current.isPresent(); i++) {
            current = current.get().subcommand(words[i]);
        }
        return current;
    }

    private static int checkHelp(Command command) {
        int count = 1;
        for (Language language : Language.values()) {
            assertFalse(command.description().get(language).isBlank(), command + " " + language);
            for (Argument argument : command.arguments()) {
                assertFalse(argument.description().get(language).isBlank(), command + " " + argument.name());
            }
        }
        List<Command> subs = new ArrayList<>(command.subcommands());
        for (Command sub : subs) {
            count += checkHelp(sub);
        }
        return count;
    }
}
