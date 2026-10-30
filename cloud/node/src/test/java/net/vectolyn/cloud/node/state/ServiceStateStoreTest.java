package net.vectolyn.cloud.node.state;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.vectolyn.cloud.config.model.TaskMode;
import net.vectolyn.cloud.node.service.ServiceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceStateStoreTest {

    @TempDir
    Path home;

    private static ServiceRecord record(String group, int index, String folder) {
        return new ServiceRecord(group + "-" + index, group, group, index, TaskMode.STATIC, ServiceState.RUNNING,
                4242, 1_700_000_000_000L, 30000 + index, folder);
    }

    private static ServiceStates states(ServiceRecord... records) {
        return new ServiceStates(ServiceStates.SCHEMA_VERSION, "node-1", List.of(records));
    }

    @Test
    void savedStateIsReadBackAndLeavesNoTemporaryFile() throws Exception {
        ServiceStateStore store = new ServiceStateStore(home);
        assertEquals(Optional.empty(), store.load(), "no file yet");
        ServiceStates states = states(record("lobby", 1, "Lobby-Server/runtime"),
                record("bedwars", 2, "temp/services/bedwars-2"));
        store.save(states);
        assertEquals(Optional.of(states), store.load());
        try (Stream<Path> files = Files.list(home.resolve("state"))) {
            assertEquals(List.of("services.json"), files.map(p -> p.getFileName().toString()).toList());
        }
        String json = Files.readString(store.file(), StandardCharsets.UTF_8);
        assertTrue(json.endsWith("}\n"), "trailing line break");
        assertTrue(json.contains("\"work-directory\" : \"Lobby-Server/runtime\""), json);
        assertTrue(json.contains("\"state\" : \"running\""), json);
        assertArrayEquals(ServiceStateStore.write(states), ServiceStateStore.write(ServiceStateStore.parse(
                json.getBytes(StandardCharsets.UTF_8))), "stable round trip");
    }

    @Test
    void anEmptyListIsValid() throws Exception {
        ServiceStates empty = ServiceStates.empty("node-1");
        assertEquals(empty, ServiceStateStore.parse(ServiceStateStore.write(empty)));
    }

    @Test
    void invalidContentIsRejectedWithoutQuotingIt() {
        String valid = new String(ServiceStateStore.write(states(record("lobby", 1, "Lobby-Server/runtime"))),
                StandardCharsets.UTF_8);
        List<String> broken = List.of(
                "",
                "null",
                "[]",
                "{",
                valid.replace("\"schema-version\" : 1", "\"schema-version\" : 2"),
                valid.replace("\"node-1\"", "\"../x\""),
                valid.replace("\"Lobby-Server/runtime\"", "\"../outside\""),
                valid.replace("\"Lobby-Server/runtime\"", "\"/etc/secret-path\""),
                valid.replace("\"Lobby-Server/runtime\"", "\"C:\\\\Windows\""),
                valid.replace("\"id\" : \"lobby-1\"", "\"id\" : \"lobby-2\""),
                valid.replace("\"index\" : 1", "\"index\" : 0"),
                valid.replace("\"pid\" : 4242", "\"pid\" : -1"),
                valid.replace("\"port\" : 30001", "\"port\" : 70000"),
                valid.replace("\"state\" : \"running\"", "\"state\" : \"exploded\""),
                valid.replace("\"mode\" : \"static\"", "\"mode\" : null"),
                valid.replace("\"pid\" : 4242", "\"pid\" : 4242, \"secret-value\" : 1"),
                valid.replace("\"pid\" : 4242", "\"pid\" : 4242, \"pid\" : 1"),
                valid.replace("\"group\" : \"lobby\"", "\"group\" : \"Lobby\""));
        for (String content : broken) {
            StateFileException e = assertThrows(StateFileException.class,
                    () -> ServiceStateStore.parse(content.getBytes(StandardCharsets.UTF_8)), content);
            assertFalse(e.getMessage().contains("outside") || e.getMessage().contains("secret-path")
                    || e.getMessage().contains("exploded"), "message quotes the content: " + e.getMessage());
        }
    }

    @Test
    void duplicateIdsAndSharedFoldersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ServiceStateStore.write(
                states(record("lobby", 1, "a/runtime"), record("lobby", 1, "b/runtime"))));
        assertThrows(IllegalArgumentException.class, () -> ServiceStateStore.write(
                states(record("lobby", 1, "a/runtime"), record("lobby", 2, "a/runtime"))));
    }

    @Test
    void aBrokenFileIsMovedAside() throws Exception {
        ServiceStateStore store = new ServiceStateStore(home);
        assertEquals(Optional.empty(), store.moveAside(Instant.EPOCH));
        Files.createDirectories(store.file().getParent());
        Files.writeString(store.file(), "{ not json");
        assertThrows(StateFileException.class, store::load);
        Path aside = store.moveAside(Instant.ofEpochMilli(1234)).orElseThrow();
        assertEquals("services.json.broken-1234", aside.getFileName().toString());
        assertEquals("{ not json", Files.readString(aside));
        assertEquals(Optional.empty(), store.load());
    }

    @Test
    void aFolderOrTooLargeFileIsNoStateFile() throws IOException {
        ServiceStateStore store = new ServiceStateStore(home);
        Files.createDirectories(store.file());
        assertThrows(StateFileException.class, store::load);
        assertThrows(StateFileException.class, () -> ServiceStateStore.parse(new byte[ServiceStateStore.MAX_BYTES + 1]));
    }
}
