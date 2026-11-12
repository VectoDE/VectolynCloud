// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.node.ops;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final Supplier<List<net.vectolyn.cloud.privacy.ModeStatistics>> statistics;
    private final Path logs;

    /** Most bytes one log search reads. */
    static final long MAX_SEARCH_BYTES = 64L * 1024 * 1024;
    /** Longest line a search returns. */
    static final int MAX_LINE = 2000;
    private static final Pattern LOG_FILE = Pattern.compile("cloud(?:\\.(\\d{1,4}))?\\.log");

    /**
     * Creates the bridge.
     *
     * @param players    the players online
     * @param services   the services of this node
     * @param archive    export and import of the configuration
     * @param statistics the statistics per game mode
     * @param logs       the folder of {@code cloud.log}
     */
    public NodePanelBridge(NetworkPlayers players, ServiceManager services, ConfigArchive archive,
                           Supplier<List<net.vectolyn.cloud.privacy.ModeStatistics>> statistics, Path logs) {
        this.players = players;
        this.services = services;
        this.archive = archive;
        this.statistics = statistics;
        this.logs = logs;
    }

    @Override
    public List<ModeStatistics> statistics() {
        List<ModeStatistics> out = new ArrayList<>();
        for (net.vectolyn.cloud.privacy.ModeStatistics m : statistics.get()) {
            out.add(new ModeStatistics(m.mode(), m.logins(), m.playtimeSeconds(), m.peakPlayers()));
        }
        out.sort(Comparator.comparing(ModeStatistics::mode));
        return out;
    }

    @Override
    public LogSearch searchLog(String text, int limit) throws IOException {
        String needle = text.toLowerCase(Locale.ROOT);
        List<LogHit> hits = new ArrayList<>();
        long budget = MAX_SEARCH_BYTES;
        for (Path file : logFiles()) {
            long size = Files.size(file);
            if (size > budget) {
                return new LogSearch(hits, true);
            }
            budget -= size;
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), decoder))) {
                String line;
                int number = 0;
                while ((line = reader.readLine()) != null) {
                    number++;
                    if (line.toLowerCase(Locale.ROOT).contains(needle)) {
                        if (hits.size() >= limit) {
                            return new LogSearch(hits, true);
                        }
                        hits.add(new LogHit(file.getFileName().toString(), number,
                                line.length() > MAX_LINE ? line.substring(0, MAX_LINE) : line));
                    }
                }
            }
        }
        return new LogSearch(hits, false);
    }

    /** {@code cloud.log}, then {@code cloud.1.log}, {@code cloud.2.log} ...: newest first; never a link. */
    private List<Path> logFiles() throws IOException {
        if (logs == null || !Files.isDirectory(logs)) {
            return List.of();
        }
        TreeMap<Integer, Path> files = new TreeMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(logs)) {
            for (Path file : stream) {
                Matcher m = LOG_FILE.matcher(file.getFileName().toString());
                if (m.matches() && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    files.put(m.group(1) == null ? 0 : Integer.parseInt(m.group(1)), file);
                }
            }
        }
        return new ArrayList<>(files.values());
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
