// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.node.ops;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.vectolyn.cloud.config.ConfigPreview;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.transfer.ConfigArchive;
import net.vectolyn.cloud.node.channel.NetworkPlayers;
import net.vectolyn.cloud.node.service.ServiceException;
import net.vectolyn.cloud.node.service.ServiceManager;
import net.vectolyn.cloud.panel.PanelNode;
import net.vectolyn.protocol.ServiceId;

/** The node side of {@link PanelNode}: players of the channel, service consoles, the config archive. */
public final class NodePanelBridge implements PanelNode {

    private final NetworkPlayers players;
    private final ServiceManager services;
    private final ConfigArchive archive;

    /**
     * Creates the bridge.
     *
     * @param players  the players online
     * @param services the services of this node
     * @param archive  export and import of the configuration
     */
    public NodePanelBridge(NetworkPlayers players, ServiceManager services, ConfigArchive archive) {
        this.players = players;
        this.services = services;
        this.archive = archive;
    }

    @Override
    public List<Player> players() {
        List<Player> out = new ArrayList<>();
        for (NetworkPlayers.Player p : players.online()) {
            out.add(new Player(p.uuid().toString(), p.name(), p.proxy() == null ? null : p.proxy().value(),
                    p.service() == null ? null : p.service().value(), p.bedrock()));
        }
        out.sort(Comparator.comparing(Player::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    @Override
    public boolean command(String serviceId, String line) {
        try {
            services.sendCommand(new ServiceId(serviceId), line);
            return true;
        } catch (ServiceException | IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public byte[] exportConfig() throws IOException {
        Map<String, byte[]> entries = new TreeMap<>(archive.exportEntries());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                // A fixed time: the same configuration gives the same archive.
                zipEntry.setLastModifiedTime(FileTime.fromMillis(0));
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Override
    public ImportPlan planImport(byte[] content) throws InvalidImport, IOException {
        ConfigArchive.Plan plan = read(content);
        List<FileChange> files = new ArrayList<>();
        for (ConfigPreview.FileChange change : plan.changes()) {
            files.add(new FileChange(change.file(), change.kind().name().toLowerCase(Locale.ROOT)));
        }
        return new ImportPlan(files, plan.diff());
    }

    @Override
    public Set<String> applyImport(byte[] content) throws InvalidImport, IOException {
        ConfigArchive.Plan plan = read(content);
        try {
            return archive.apply(plan);
        } catch (ConfigException e) {
            throw new InvalidImport(e.getMessage());
        }
    }

    private ConfigArchive.Plan read(byte[] content) throws InvalidImport, IOException {
        try {
            return archive.read(content);
        } catch (ConfigException e) {
            throw new InvalidImport(e.getMessage());
        } catch (java.util.zip.ZipException e) {
            throw new InvalidImport("The file is not a valid zip archive.");
        }
    }
}
