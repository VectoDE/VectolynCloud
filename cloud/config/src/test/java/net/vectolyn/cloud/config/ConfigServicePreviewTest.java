// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.error.ErrorCode;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.Language;
import net.vectolyn.cloud.config.model.ProxyConfig;
import net.vectolyn.cloud.config.operation.PutGroup;
import net.vectolyn.cloud.config.operation.PutTask;
import net.vectolyn.cloud.config.operation.RemoveTask;
import net.vectolyn.cloud.config.operation.SetLanguage;
import net.vectolyn.cloud.config.operation.SetProxy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link ConfigService#preview}: the diff of a change, nothing written, nobody notified. */
class ConfigServicePreviewTest {

    @TempDir
    Path root;

    @Test
    void previewShowsTheDiffAndWritesNothing() throws Exception {
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            byte[] before = Files.readAllBytes(root.resolve("config/cloud.yml"));
            List<ConfigChanged> events = new CopyOnWriteArrayList<>();
            service.addListener(events::add);
            ConfigSnapshot snapshot = service.snapshot();

            ConfigPreview preview = service.preview(new SetLanguage(Language.DE));

            assertEquals(1, preview.files().size());
            ConfigPreview.FileChange change = preview.files().getFirst();
            assertEquals("cloud.yml", change.file());
            assertEquals(ConfigPreview.Kind.CHANGED, change.kind());
            assertTrue(change.diff().contains("-language: en\n+language: de\n"), change.diff());
            assertSame(snapshot, service.snapshot());
            assertEquals(Language.DE, preview.after().cloud().language());
            assertArrayEquals(before, Files.readAllBytes(root.resolve("config/cloud.yml")));
            assertTrue(events.isEmpty());
        }
    }

    @Test
    void addedAndRemovedFiles() throws Exception {
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            ConfigPreview added = service.preview(List.of(new PutGroup("lobby", Samples.lobbyGroup()),
                    new PutTask("lobby", Samples.lobbyTask())));
            assertEquals(List.of("groups/lobby.yml", "tasks/lobby.yml"),
                    added.files().stream().map(ConfigPreview.FileChange::file).toList());
            assertTrue(added.files().stream().allMatch(f -> f.kind() == ConfigPreview.Kind.ADDED));
            assertTrue(added.diff().contains("--- /dev/null\n+++ b/tasks/lobby.yml\n"), added.diff());
            assertTrue(Files.notExists(root.resolve("config/groups/lobby.yml")));

            service.apply(List.of(new PutGroup("lobby", Samples.lobbyGroup()), new PutTask("lobby", Samples.lobbyTask())));
            ConfigPreview removed = service.preview(new RemoveTask("lobby"));
            assertEquals(ConfigPreview.Kind.REMOVED, removed.files().getFirst().kind());
            assertTrue(removed.diff().contains("+++ /dev/null"), removed.diff());
            assertTrue(Files.exists(root.resolve("config/tasks/lobby.yml")));
        }
    }

    @Test
    void anUnchangedValueGivesAnEmptyPreview() throws Exception {
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            assertTrue(service.preview(new SetLanguage(service.snapshot().cloud().language())).isEmpty());
        }
    }

    @Test
    void anInvalidChangeFailsLikeApply() throws Exception {
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            ConfigException e = assertThrows(ConfigException.class,
                    () -> service.preview(new SetProxy(new ProxyConfig("0.0.0.0", 0, 19132))));
            assertEquals(ErrorCode.OUT_OF_RANGE, e.errors().getFirst().code());
        }
    }

    @Test
    void aMemberCannotPreviewFilesOfTheHead() throws Exception {
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            service.mirrorMode(true);
            ConfigException e = assertThrows(ConfigException.class,
                    () -> service.preview(new PutGroup("lobby", Samples.lobbyGroup())));
            assertEquals(ErrorCode.MANAGED_BY_HEAD, e.errors().getFirst().code());
        }
    }
}
