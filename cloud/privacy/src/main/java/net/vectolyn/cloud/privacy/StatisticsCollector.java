// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.vectolyn.cloud.config.io.AtomicFileWriter;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Playtime, logins and player counts per game mode, in {@code state/statistics.json}.
 *
 * <p>What is kept: per mode three sums (see {@link ModeStatistics}). What is not kept: names, UUIDs,
 * addresses or any time stamp of a single player. To compute playtime the collector remembers, in
 * memory only, in which mode each online player currently is and since when; that entry is dropped
 * when the player leaves, and nothing of it is written to disk. All methods are thread safe.
 */
public final class StatisticsCollector {

    /** Most modes in one file. */
    public static final int MAX_MODES = 1_000;

    /** Largest file read: 4 MiB. */
    public static final int MAX_BYTES = 4 * 1024 * 1024;

    private static final int VERSION = 1;
    private static final JsonMapper JSON =
            JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final Path file;
    private final Clock clock;
    private final Map<String, long[]> totals = new LinkedHashMap<>(); // logins, seconds, peak
    private final Map<String, Integer> online = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();

    /** The file is not a valid statistics file. */
    public static final class FormatException extends Exception {
        private static final long serialVersionUID = 1L;

        FormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record Session(String mode, Instant since) {
    }

    private StatisticsCollector(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    /**
     * Opens the collector with what an earlier run saved.
     *
     * @param file  the statistics file; a missing file means empty statistics
     * @param clock time source
     * @return the collector
     * @throws IOException     if the file cannot be read
     * @throws FormatException if the file is not valid
     */
    public static StatisticsCollector open(Path file, Clock clock) throws IOException, FormatException {
        Objects.requireNonNull(file, "file");
        StatisticsCollector collector = new StatisticsCollector(file, Objects.requireNonNull(clock, "clock"));
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            for (ModeStatistics m : decode(in.readNBytes(MAX_BYTES + 1))) {
                collector.totals.put(m.mode(), new long[] {m.logins(), m.playtimeSeconds(), m.peakPlayers()});
            }
        } catch (NoSuchFileException e) {
            // no statistics yet
        }
        return collector;
    }

    /**
     * A player joined the network on a server of {@code mode}, or moved to one.
     *
     * @param player player id, used only to match the later switch or quit
     * @param mode   game mode (group) of the server
     */
    public synchronized void arrived(UUID player, String mode) {
        if (!ModeStatistics.MODE.matcher(mode).matches()) {
            return;
        }
        endSession(player);
        startSession(player, mode);
    }

    /**
     * A player left the network.
     *
     * @param player player id
     */
    public synchronized void left(UUID player) {
        endSession(player);
    }

    /** Counts the time of everyone who is still online, then forgets them (the node is stopping). */
    public synchronized void closeAll() {
        for (UUID player : List.copyOf(sessions.keySet())) {
            endSession(player);
        }
    }

    /**
     * The totals so far, including the time of players who are online right now.
     *
     * @return one entry per mode, sorted by name
     */
    public synchronized List<ModeStatistics> snapshot() {
        Instant now = clock.instant();
        Map<String, long[]> copy = new HashMap<>();
        totals.forEach((mode, values) -> copy.put(mode, values.clone()));
        for (Session session : sessions.values()) {
            // A session exists only for a mode that has totals (startSession).
            Objects.requireNonNull(copy.get(session.mode()), "mode")[1] += seconds(session.since(), now);
        }
        List<ModeStatistics> result = new ArrayList<>();
        copy.forEach((mode, v) -> result.add(new ModeStatistics(mode, v[0], v[1], (int) v[2])));
        result.sort(Comparator.comparing(ModeStatistics::mode));
        return result;
    }

    /**
     * Writes the totals (not the sessions of players who are online) to the file, atomically.
     *
     * @throws IOException if the file cannot be written
     */
    public synchronized void save() throws IOException {
        List<ModeStatistics> list = new ArrayList<>();
        totals.forEach((mode, v) -> list.add(new ModeStatistics(mode, v[0], v[1], (int) v[2])));
        list.sort(Comparator.comparing(ModeStatistics::mode));
        new AtomicFileWriter().write(file, encode(list));
    }

    private void startSession(UUID player, String mode) {
        if (!totals.containsKey(mode) && totals.size() >= MAX_MODES) {
            return;
        }
        long[] values = totals.computeIfAbsent(mode, k -> new long[3]);
        values[0]++;
        int now = online.merge(mode, 1, Integer::sum);
        values[2] = Math.max(values[2], now);
        sessions.put(player, new Session(mode, clock.instant()));
    }

    private void endSession(UUID player) {
        Session session = sessions.remove(player);
        if (session == null) {
            return;
        }
        Objects.requireNonNull(totals.get(session.mode()), "mode")[1] += seconds(session.since(), clock.instant());
        online.merge(session.mode(), -1, Integer::sum);
    }

    private static long seconds(Instant from, Instant to) {
        return Math.max(0, Duration.between(from, to).toSeconds());
    }

    static byte[] encode(List<ModeStatistics> list) {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", VERSION);
        ArrayNode modes = root.putArray("modes");
        for (ModeStatistics m : list) {
            ObjectNode node = modes.addObject();
            node.put("mode", m.mode());
            node.put("logins", m.logins());
            node.put("playtime-seconds", m.playtimeSeconds());
            node.put("peak-players", m.peakPlayers());
        }
        return JSON.writeValueAsBytes(root);
    }

    /**
     * Decodes a statistics file strictly: unknown versions or fields, mistyped or negative values,
     * invalid or duplicate mode names and too many modes are errors.
     *
     * @param content JSON bytes
     * @return the entries
     * @throws FormatException if the content is not valid
     */
    public static List<ModeStatistics> decode(byte[] content) throws FormatException {
        try {
            if (content.length > MAX_BYTES) {
                throw new IllegalArgumentException("file too large");
            }
            JsonNode root = JSON.readTree(content);
            if (root == null || !root.isObject() || root.size() != 2 || root.path("version").asInt(-1) != VERSION
                    || !root.path("modes").isArray() || root.path("modes").size() > MAX_MODES) {
                throw new IllegalArgumentException("not a statistics file");
            }
            Map<String, ModeStatistics> result = new LinkedHashMap<>();
            for (JsonNode node : root.get("modes")) {
                if (!node.isObject() || node.size() != 4 || !node.path("mode").isString()
                        || !node.path("logins").isIntegralNumber() || !node.path("playtime-seconds").isIntegralNumber()
                        || !node.path("peak-players").isIntegralNumber() || !node.path("peak-players").canConvertToInt()) {
                    throw new IllegalArgumentException("invalid entry");
                }
                ModeStatistics m = new ModeStatistics(node.get("mode").asString(), node.get("logins").asLong(),
                        node.get("playtime-seconds").asLong(), node.get("peak-players").asInt());
                if (result.put(m.mode(), m) != null) {
                    throw new IllegalArgumentException("duplicate mode");
                }
            }
            return new ArrayList<>(result.values());
        } catch (RuntimeException e) {
            throw new FormatException("invalid statistics file", e);
        }
    }
}
