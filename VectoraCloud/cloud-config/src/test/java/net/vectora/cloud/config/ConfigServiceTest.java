package net.vectora.cloud.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.error.ErrorCode;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.operation.AcceptEula;
import net.vectora.cloud.config.operation.CompleteSetup;
import net.vectora.cloud.config.operation.ConfigOperation;
import net.vectora.cloud.config.operation.PutGroup;
import net.vectora.cloud.config.operation.PutTask;
import net.vectora.cloud.config.operation.SetGroupHeap;
import net.vectora.cloud.config.operation.SetLanguage;
import net.vectora.cloud.config.operation.SetServerWanted;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigServiceTest {

    @TempDir
    Path root;

    @Test
    void openingAnEmptyHomeWritesDefaults() throws Exception {
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            assertEquals(ConfigSnapshot.defaults(), service.snapshot());
        }
        assertTrue(Files.isRegularFile(root.resolve("cloud.yml")));
        assertTrue(Files.isRegularFile(root.resolve("nodes.yml")));
        assertTrue(Files.isDirectory(root.resolve("groups")));
        assertTrue(Files.isDirectory(root.resolve("tasks")));
    }

    @Test
    void applyValidatesWritesAndNotifies() throws Exception {
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            List<ConfigChanged> events = new CopyOnWriteArrayList<>();
            service.addListener(events::add);

            ConfigChanged change = service.apply(List.of(new PutGroup("lobby", Samples.lobbyGroup()),
                    new PutTask("lobby", Samples.lobbyTask()), new SetLanguage(Language.DE)));

            assertEquals(Set.of("cloud.yml", "groups/lobby.yml", "tasks/lobby.yml"), change.files());
            assertEquals(ConfigChanged.Origin.OPERATION, change.origin());
            assertEquals(3, change.operations().size());
            assertEquals(List.of(change), events);
            assertEquals(Language.DE, service.snapshot().cloud().language());
            assertTrue(Files.readString(root.resolve("cloud.yml")).contains("language: de"));
        }
        try (ConfigService reopened = ConfigService.open(CloudHome.of(root))) {
            assertEquals(Language.DE, reopened.snapshot().cloud().language());
            assertEquals(Samples.lobbyGroup(), reopened.snapshot().groups().get("lobby"));
        }
    }

    @Test
    void invalidChangesWriteNothing() throws Exception {
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            List<ConfigChanged> events = new CopyOnWriteArrayList<>();
            service.addListener(events::add);
            String before = Files.readString(root.resolve("cloud.yml"));
            ConfigSnapshot snapshot = service.snapshot();

            ConfigException e = assertThrows(ConfigException.class, () -> service.apply(new CompleteSetup()));
            assertEquals(ErrorCode.EULA_REQUIRED, e.errors().getFirst().code());
            ConfigException task = assertThrows(ConfigException.class, () -> service.apply(new PutTask("lobby", Samples.lobbyTask())));
            assertEquals(ErrorCode.UNKNOWN_GROUP, task.errors().getFirst().code());

            assertEquals(before, Files.readString(root.resolve("cloud.yml")));
            assertFalse(Files.exists(root.resolve("tasks/lobby.yml")));
            assertEquals(snapshot, service.snapshot());
            assertTrue(events.isEmpty());
        }
    }

    @Test
    void unchangedResultNotifiesNobody() throws Exception {
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            List<ConfigChanged> events = new CopyOnWriteArrayList<>();
            service.addListener(events::add);
            assertTrue(service.apply(new SetServerWanted(ServerKind.LOBBY, true)).isEmpty());
            assertTrue(events.isEmpty());
        }
    }

    @Test
    void failingListenerDoesNotStopTheOthers() throws Exception {
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            List<ConfigChanged> events = new CopyOnWriteArrayList<>();
            service.addListener(change -> {
                throw new IllegalStateException("listener bug");
            });
            service.addListener(events::add);
            service.apply(new AcceptEula());
            assertEquals(1, events.size());
        }
    }

    @Test
    void concurrentChangesAreSerialized() throws Exception {
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            int groups = 8;
            List<ConfigOperation> setup = new ArrayList<>();
            for (int i = 0; i < groups; i++) {
                setup.add(new PutGroup("g" + i, Samples.lobbyGroup()));
            }
            service.apply(setup);
            List<ConfigChanged> events = new CopyOnWriteArrayList<>();
            service.addListener(events::add);

            ExecutorService pool = Executors.newFixedThreadPool(groups);
            try {
                List<Future<ConfigChanged>> futures = new ArrayList<>();
                for (int i = 0; i < groups; i++) {
                    String group = "g" + i;
                    for (int mb = 1; mb <= 5; mb++) {
                        MemorySize max = MemorySize.ofMegabytes(1024 + mb);
                        futures.add(pool.submit(() -> service.apply(new SetGroupHeap(group, MemorySize.parse("256M"), max))));
                    }
                }
                for (Future<ConfigChanged> future : futures) {
                    future.get();
                }
            } finally {
                pool.shutdown();
            }

            assertEquals(groups * 5, events.size());
            for (int i = 1; i < events.size(); i++) {
                assertEquals(events.get(i - 1).current(), events.get(i).previous(), "events form one chain");
            }
            assertEquals(service.snapshot(), events.getLast().current());
            try (ConfigService reopened = ConfigService.open(CloudHome.of(root))) {
                assertEquals(service.snapshot(), reopened.snapshot(), "disk matches memory");
            }
        }
    }

    @Test
    void reloadPicksUpValidEditsAndRejectsInvalidOnes() throws Exception {
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            assertEquals(Optional.empty(), service.reload());
            Path cloud = root.resolve("cloud.yml");
            Files.writeString(cloud, Files.readString(cloud).replace("language: en", "language: de"));
            ConfigChanged change = service.reload().orElseThrow();
            assertEquals(ConfigChanged.Origin.FILE, change.origin());
            assertEquals(Set.of("cloud.yml"), change.files());
            assertEquals(Language.DE, service.snapshot().cloud().language());

            ConfigSnapshot good = service.snapshot();
            Files.writeString(cloud, Files.readString(cloud).replace("port: 25565", "port: 99999"));
            assertThrows(ConfigException.class, service::reload);
            assertEquals(good, service.snapshot());

            Files.delete(cloud);
            ConfigException missing = assertThrows(ConfigException.class, service::reload);
            assertEquals(ErrorCode.FILE_MISSING, missing.errors().getFirst().code());
            assertEquals(good, service.snapshot(), "a deleted cloud.yml does not reset the configuration");
        }
    }

    @Test
    void openRefusesAnInvalidHome() throws IOException {
        Files.writeString(root.resolve("nodes.yml"), "schema-version: 1\nnodes: []\n");
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigService.open(CloudHome.of(root)));
        assertTrue(e.errors().stream().anyMatch(err -> err.code() == ErrorCode.EMPTY));
        assertFalse(Files.exists(root.resolve("cloud.yml")), "nothing is written for an invalid home");
    }

    @Test
    void closedServiceRejectsChanges() throws Exception {
        ConfigService service = ConfigService.open(CloudHome.of(root));
        service.close();
        assertThrows(IllegalStateException.class, () -> service.apply(new AcceptEula()));
    }
}
