package net.vectolyn.cloud.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.error.ErrorCode;
import net.vectolyn.cloud.config.io.ConfigYaml;
import net.vectolyn.cloud.config.model.Language;
import net.vectolyn.cloud.config.model.NodesConfig;
import net.vectolyn.cloud.config.operation.PutGroup;
import net.vectolyn.cloud.config.operation.PutTask;
import net.vectolyn.cloud.config.operation.RemoveTask;
import net.vectolyn.cloud.config.operation.SetLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The mirror mode of a cluster member: nodes, groups and tasks belong to the head. */
class ConfigServiceMirrorTest {

    @TempDir
    Path root;

    @Test
    void applyRefusesMirroredFilesButNotCloudYml() throws Exception {
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            service.apply(List.of(new PutGroup("lobby", Samples.lobbyGroup()), new PutTask("lobby", Samples.lobbyTask())));
            service.mirrorMode(true);
            assertTrue(service.mirrorMode());

            ConfigException e = assertThrows(ConfigException.class, () -> service.apply(new RemoveTask("lobby")));
            assertEquals(ErrorCode.MANAGED_BY_HEAD, e.errors().getFirst().code());
            assertEquals("tasks/lobby.yml", e.errors().getFirst().file());
            assertTrue(e.describe(Language.DE).contains("Cluster-Head"), e.describe(Language.DE));
            assertTrue(service.snapshot().tasks().containsKey("lobby"), "nothing changed");

            service.apply(new SetLanguage(Language.DE));
            assertEquals(Language.DE, service.snapshot().cloud().language());
        }
    }

    @Test
    void applyMirrorWritesTheSetAndDeletesTheRest() throws Exception {
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            service.apply(List.of(new PutGroup("lobby", Samples.lobbyGroup()), new PutTask("lobby", Samples.lobbyTask())));
            Files.writeString(root.resolve("config/groups/stray.yml"), "not even yaml: [");
            List<ConfigChanged> events = new CopyOnWriteArrayList<>();
            service.addListener(events::add);

            assertThrows(IllegalStateException.class,
                    () -> service.applyMirror(NodesConfig.defaults(), Map.of(), Map.of()));
            service.mirrorMode(true);
            ConfigChanged change = service.applyMirror(NodesConfig.defaults(), Map.of("lobby", Samples.lobbyGroup()),
                    Map.of());

            assertEquals(ConfigChanged.Origin.MIRROR, change.origin());
            assertTrue(change.files().contains("tasks/lobby.yml"));
            assertTrue(change.files().contains("groups/stray.yml"));
            assertFalse(Files.exists(root.resolve("config/tasks/lobby.yml")));
            assertFalse(Files.exists(root.resolve("config/groups/stray.yml")));
            assertArrayEquals(ConfigYaml.write(Samples.lobbyGroup()), Files.readAllBytes(root.resolve("config/groups/lobby.yml")));
            assertEquals(1, events.size());
        }
    }

    @Test
    void anInvalidMirrorIsNotWritten() throws Exception {
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            service.mirrorMode(true);
            // A task on a node that nodes.yml does not have.
            assertThrows(ConfigException.class, () -> service.applyMirror(NodesConfig.defaults(),
                    Map.of("lobby", Samples.lobbyGroup()), Map.of("lobby", Samples.lobbyTask().withNodes(List.of("node-9")))));
            assertFalse(Files.exists(root.resolve("config/groups/lobby.yml")));
        }
    }

    @Test
    void aHandEditOfAMirroredFileIsUndoneOnReload() throws Exception {
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            service.mirrorMode(true);
            service.applyMirror(NodesConfig.defaults(), Map.of("lobby", Samples.lobbyGroup()), Map.of());
            Path group = root.resolve("config/groups/lobby.yml");
            byte[] mirrored = Files.readAllBytes(group);
            Files.writeString(group, new String(mirrored, StandardCharsets.UTF_8).replace("768M", "2G"));
            Files.writeString(root.resolve("config/tasks/extra.yml"), "schema-version: 2\n");
            String cloud = Files.readString(root.resolve("config/cloud.yml"));
            Files.writeString(root.resolve("config/cloud.yml"), cloud.replace("language: en", "language: de"));

            assertTrue(service.reload().isPresent(), "cloud.yml is this node's own");

            assertEquals(Language.DE, service.snapshot().cloud().language());
            assertArrayEquals(mirrored, Files.readAllBytes(group), "the mirrored content is back");
            assertFalse(Files.exists(root.resolve("config/tasks/extra.yml")));
            assertEquals(Samples.lobbyGroup(), service.snapshot().groups().get("lobby"));
        }
    }
}
