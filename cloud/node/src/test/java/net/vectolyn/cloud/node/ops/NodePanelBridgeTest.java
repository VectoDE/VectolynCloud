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
                    new ConfigArchive(source, List.of()));
            zip = bridge.exportConfig();
            assertFalse(new String(zip, StandardCharsets.ISO_8859_1).contains("test-secret-0000"), "never a secret");
            assertTrue(bridge.players().isEmpty());
        }
        try (ConfigService target = ConfigService.open(CloudHome.of(temp.resolve("b")))) {
            NodePanelBridge bridge = new NodePanelBridge(new NetworkPlayers((a, b) -> Optional.empty()), null,
                    new ConfigArchive(target, List.of()));
            PanelNode.ImportPlan plan = bridge.planImport(zip);
            assertEquals(List.of(new PanelNode.FileChange("cloud.yml", "changed")), plan.files());
            assertTrue(plan.diff().contains("+language: de"), plan.diff());
            assertEquals(Language.EN, target.snapshot().cloud().language(), "a preview writes nothing");
            assertTrue(bridge.applyImport(zip).contains("cloud.yml"));
            assertEquals(Language.DE, target.snapshot().cloud().language());
            assertThrows(PanelNode.InvalidImport.class, () -> bridge.planImport("not a zip".getBytes(StandardCharsets.UTF_8)));
        }
    }
}
