// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.vectolyn.cloud.config.model.Language;
import net.vectolyn.cloud.console.command.Argument;
import net.vectolyn.cloud.console.command.Command;
import net.vectolyn.cloud.console.command.CommandDispatcher;
import net.vectolyn.cloud.console.command.CommandRegistry;
import net.vectolyn.protocol.NodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Tab completion and help of the {@code cluster} command, against a real head and member on loopback. */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class ClusterCommandsTest {

    @TempDir
    Path dir;

    private TestNode head;
    private TestNode member;

    @BeforeEach
    void setUp() throws Exception {
        head = new TestNode(dir.resolve("head"), "node-1", "1.0.0");
        member = new TestNode(dir.resolve("member"), "node-2", "1.0.0");
    }

    @AfterEach
    void tearDown() {
        member.close();
        head.close();
    }

    private static CommandRegistry registry(ClusterService cluster) {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new ClusterCommands(cluster).command());
        return registry;
    }

    @Test
    void completionOffersSubcommandsTokenIdsAndMembers() throws Exception {
        CommandRegistry standalone = registry(head.cluster);
        assertEquals(List.of("info", "init", "join", "nodes", "remove", "status", "sync", "token"),
                CommandDispatcher.complete(standalone, "cluster "));
        assertEquals(List.of("create", "delete", "list", "remove", "revoke"),
                CommandDispatcher.complete(standalone, "cluster token "));
        // Not a head yet: nothing to offer, and completion does not fail.
        assertEquals(List.of(), CommandDispatcher.complete(standalone, "cluster token revoke "));
        assertEquals(List.of(), CommandDispatcher.complete(standalone, "cluster remove "));
        assertEquals(List.of("7011"), CommandDispatcher.complete(standalone, "cluster init 10.0.0.1 "));

        head.cluster.init("127.0.0.1", 0, null);
        int port = head.clusterPort();
        String open = head.cluster.createToken(new NodeId("node-3"), Duration.ofMinutes(10)).info().id();
        String join = head.cluster.createToken(new NodeId("node-2"), Duration.ofMinutes(10)).token().reveal();
        CommandRegistry onHead = registry(head.cluster);
        List<String> ids = CommandDispatcher.complete(onHead, "cluster token revoke ");
        assertEquals(2, ids.size(), ids.toString());
        assertTrue(ids.contains(open), ids.toString());
        assertTrue(CommandDispatcher.complete(onHead, "cluster token delete " + open.substring(0, 3)).contains(open));
        assertEquals(List.of("10m", "1h", "24h", "30m").stream().sorted().toList(),
                CommandDispatcher.complete(onHead, "cluster token create node-4 "));
        assertEquals(List.of(), CommandDispatcher.complete(onHead, "cluster remove "), "the head itself is no candidate");

        member.cluster.join("127.0.0.1", port, new NodeId("node-1"), join,
                head.cluster.status().pin().orElseThrow().toString());
        assertEquals(List.of("node-2"), CommandDispatcher.complete(onHead, "cluster remove "));
        assertEquals(List.of(open), CommandDispatcher.complete(onHead, "cluster token revoke "), "the join used its token");
        // A member offers neither tokens nor members: those belong to the head.
        CommandRegistry onMember = registry(member.cluster);
        assertEquals(List.of(), CommandDispatcher.complete(onMember, "cluster token revoke "));
        assertEquals(List.of(), CommandDispatcher.complete(onMember, "cluster remove "));
    }

    @Test
    void everySubcommandHasHelpInBothLanguagesAndTheCommandIsSensitive() {
        CommandRegistry registry = registry(head.cluster);
        Command cluster = registry.find("cluster").orElseThrow();
        assertEquals(10, check(cluster), "cluster and its subcommands");
        assertTrue(registry.isSensitive("cluster join 10.0.0.1:7011 node-1 secret sha256:x"));
        assertTrue(registry.isSensitive("cluster token create node-2"));
    }

    private static int check(Command command) {
        int count = 1;
        for (Language language : Language.values()) {
            assertFalse(command.description().get(language).isBlank(), command + " " + language);
            for (Argument argument : command.arguments()) {
                assertFalse(argument.description().get(language).isBlank(), command + " " + argument.name());
            }
        }
        for (Command sub : command.subcommands()) {
            count += check(sub);
        }
        return count;
    }
}
