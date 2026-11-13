// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.node.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.ConfigService;
import net.vectolyn.cloud.config.model.Language;
import net.vectolyn.cloud.config.operation.SetLanguage;
import net.vectolyn.cloud.config.transfer.ConfigArchive;
import net.vectolyn.cloud.node.channel.NetworkPlayers;
import net.vectolyn.cloud.panel.PanelNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodePanelBridgeTest {

    @TempDir
    Path temp;

    @Test
    void exportPreviewAndImportGoThroughTheArchive() throws Exception {
        byte[] zip;
        try (ConfigService source = ConfigService.open(CloudHome.of(temp.resolve("a")))) {
            source.apply(List.of(new SetLanguage(Language.DE)));
            Files.createDirectories(source.home().secretsDir());
            Files.writeString(source.home().secretsFile(), "VECTOLYN_DB_PASSWORD=test-secret-0000\n");
            NodePanelBridge bridge = new NodePanelBridge(new NetworkPlayers((a, b) -> Optional.empty()), null,
                    new ConfigArchive(source, List.of()), List::of, null);
            zip = bridge.exportConfig();
            assertFalse(new String(zip, StandardCharsets.ISO_8859_1).contains("test-secret-0000"), "never a secret");
            assertTrue(bridge.players().isEmpty());
        }
        try (ConfigService target = ConfigService.open(CloudHome.of(temp.resolve("b")))) {
            NodePanelBridge bridge = new NodePanelBridge(new NetworkPlayers((a, b) -> Optional.empty()), null,
                    new ConfigArchive(target, List.of()), List::of, null);
            PanelNode.ImportPlan plan = bridge.planImport(zip);
            assertEquals(List.of(new PanelNode.FileChange("cloud.yml", "changed")), plan.files());
            assertTrue(plan.diff().contains("+language: de"), plan.diff());
            assertEquals(Language.EN, target.snapshot().cloud().language(), "a preview writes nothing");
            assertTrue(bridge.applyImport(zip).contains("cloud.yml"));
            assertEquals(Language.DE, target.snapshot().cloud().language());
            assertThrows(PanelNode.InvalidImport.class, () -> bridge.planImport("not a zip".getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void theLogSearchReadsNewestFirstAndStopsAtItsLimit() throws Exception {
        Path logs = Files.createDirectories(temp.resolve("logs"));
        Files.writeString(logs.resolve("cloud.log"), "INFO start\nWARN disk almost full\n");
        Files.writeString(logs.resolve("cloud-2026-11-11.log"), "WARN disk yesterday\n");
        Files.writeString(logs.resolve("cloud-2026-11-12.log"), "WARN disk today, first file\n");
        Files.writeString(logs.resolve("cloud-2026-11-12.1.log"), "WARN disk today, second file\n");
        Files.writeString(logs.resolve("cloud-2026-11-12.txt"), "WARN disk not a log\n");
        Files.writeString(logs.resolve("cloud.1.log"), "WARN Disk old\n");
        Files.writeString(logs.resolve("cloud.12.log"), "WARN disk oldest\n");
        Files.writeString(logs.resolve("other.log"), "WARN disk not ours\n");
        Files.write(logs.resolve("cloud.2.log"), new byte[] {'d', 'i', 's', 'k', (byte) 0xff, '\n'});
        NodePanelBridge bridge = new NodePanelBridge(new NetworkPlayers((a, b) -> Optional.empty()), null, null,
                () -> List.of(new net.vectolyn.cloud.privacy.ModeStatistics("lobby", 3, 120, 2)), logs);
        PanelNode.LogSearch all = bridge.searchLog("DISK", 10);
        assertEquals(List.of("cloud.log:2", "cloud-2026-11-12.1.log:1", "cloud-2026-11-12.log:1",
                        "cloud-2026-11-11.log:1", "cloud.1.log:1", "cloud.2.log:1", "cloud.12.log:1"),
                all.hits().stream().map(h -> h.file() + ":" + h.line()).toList());
        assertFalse(all.truncated());
        assertTrue(all.hits().get(5).text().startsWith("disk"), "broken UTF-8 is replaced, not fatal");
        PanelNode.LogSearch two = bridge.searchLog("disk", 2);
        assertEquals(2, two.hits().size());
        assertTrue(two.truncated());
        assertEquals(List.of(new PanelNode.ModeStatistics("lobby", 3, 120, 2)), bridge.statistics());
    }
}
