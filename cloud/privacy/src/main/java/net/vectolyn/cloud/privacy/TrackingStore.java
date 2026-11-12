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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import net.vectolyn.cloud.config.io.AtomicFileWriter;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Per-player tracking in {@code state/tracking.json}, only for players who agreed.
 *
 * <ul>
 *   <li>Nothing is recorded about a player without a consent: {@link #arrived} and {@link #left}
 *       ignore everyone else.</li>
 *   <li>{@link #grant} records the consent (time and notice version) and starts counting from that
 *       moment; nothing before it is kept.</li>
 *   <li>{@link #revoke} deletes the player's whole record at once, consent included.</li>
 *   <li>{@link #find} is the access: the full record, including the time of a visit that is still
 *       running.</li>
 *   <li>{@link #purge} deletes records whose last visit is older than the retention period, and the
 *       store does it when it opens and on a schedule ({@link PrivacyRuntime}).</li>
 * </ul>
 *
 * Writes are atomic. All methods are thread safe.
 */
public final class TrackingStore {

    /** Most players in one file. */
    public static final int MAX_PLAYERS = 200_000;

    /** Most game modes per player. */
    public static final int MAX_MODES = 200;

    /** Largest file read: 64 MiB. */
    public static final int MAX_BYTES = 64 * 1024 * 1024;

    private static final int VERSION = 1;
    private static final JsonMapper JSON =
            JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final Path file;
    private final int retentionDays;
    private final Clock clock;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    /** The file is not a valid tracking file. */
    public static final class FormatException extends Exception {
        private static final long serialVersionUID = 1L;

        FormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Raised when the store holds {@link #MAX_PLAYERS} players already. */
    public static final class FullException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FullException() {
            super("tracking store is full");
        }
    }

    private static final class Entry {
        @Nullable String name;
        @Nullable Instant consentAt;
        int noticeVersion;
        @Nullable Instant firstSeen;
        @Nullable Instant lastSeen;
        long logins;
        long playtime;
        final Map<String, long[]> modes = new TreeMap<>();
        // Running visit: in memory only, counted into the totals when it ends.
        @Nullable String sessionMode;
        @Nullable Instant sessionSince;
    }

    private TrackingStore(Path file, int retentionDays, Clock clock) {
        this.file = file;
        this.retentionDays = retentionDays;
        this.clock = clock;
    }

    /**
     * Opens the store and deletes what is due.
     *
     * @param file          the file; a missing file means no players
     * @param retentionDays days after the last visit until a record is deleted
     * @param clock         time source
     * @return the store
     * @throws IOException     if the file cannot be read or written
     * @throws FormatException if the file is not valid
     */
    public static TrackingStore open(Path file, int retentionDays, Clock clock) throws IOException, FormatException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(clock, "clock");
        if (retentionDays < 1) {
            throw new IllegalArgumentException("retentionDays must be at least 1");
        }
        TrackingStore store = new TrackingStore(file, retentionDays, clock);
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            for (TrackedPlayer p : decode(in.readNBytes(MAX_BYTES + 1))) {
                Entry e = new Entry();
                e.name = p.name();
                e.consentAt = p.consentAt();
                e.noticeVersion = p.noticeVersion();
                e.firstSeen = p.firstSeen();
                e.lastSeen = p.lastSeen();
                e.logins = p.logins();
                e.playtime = p.playtimeSeconds();
                p.modes().forEach(m -> e.modes.put(m.mode(), new long[] {m.logins(), m.playtimeSeconds()}));
                store.entries.put(p.uuid(), e);
            }
        } catch (NoSuchFileException e) {
            // no records yet
        }
        store.purge();
        return store;
    }

    /**
     * Whether the player agreed and the record has not expired.
     *
     * @param player the player's id
     * @return true if the player is tracked
     */
    public synchronized boolean consented(UUID player) {
        Entry e = entries.get(player);
        return e != null && !due(e, clock.instant());
    }

    /**
     * Records the player's consent and starts counting. If the player already agreed, the consent
     * is kept as it is and only the name and the running visit are updated.
     *
     * @param player player id
     * @param name   current name
     * @param mode   game mode the player is on right now, or null if unknown
     * @throws IOException if the file cannot be written
     * @throws FullException if the store is full
     */
    public synchronized void grant(UUID player, String name, @Nullable String mode) throws IOException {
        Instant now = clock.instant();
        Entry e = entries.get(player);
        if (e == null || due(e, now)) {
            if (e == null && entries.size() >= MAX_PLAYERS) {
                throw new FullException();
            }
            e = new Entry();
            e.consentAt = now;
            e.noticeVersion = TrackingNotice.VERSION;
            e.firstSeen = now;
            entries.put(player, e);
        }
        checkName(name);
        e.name = name;
        e.lastSeen = now;
        if (mode != null && ModeStatistics.MODE.matcher(mode).matches() && e.sessionMode == null) {
            // The visit that is running counts from the consent on, and is a login of this mode.
            count(e, mode);
            e.sessionMode = mode;
            e.sessionSince = now;
        }
        write();
    }

    /**
     * Deletes everything about the player, consent included.
     *
     * @param player player id
     * @return whether there was a record
     * @throws IOException if the file cannot be written
     */
    public synchronized boolean revoke(UUID player) throws IOException {
        boolean existed = entries.remove(player) != null;
        if (existed) {
            write();
        }
        return existed;
    }

    /**
     * The player joined the network or moved to a game mode. Ignored unless the player agreed.
     *
     * @param player player id
     * @param name   current name
     * @param mode   game mode of the server
     */
    public synchronized void arrived(UUID player, String name, String mode) {
        Entry e = entries.get(player);
        Instant now = clock.instant();
        if (e == null || due(e, now) || !TrackedPlayer.NAME.matcher(name).matches()
                || !ModeStatistics.MODE.matcher(mode).matches()) {
            return;
        }
        endVisit(e, now);
        e.name = name;
        e.lastSeen = now;
        count(e, mode);
        e.sessionMode = mode;
        e.sessionSince = now;
    }

    /**
     * The player left the network. Ignored unless the player agreed.
     *
     * @param player player id
     */
    public synchronized void left(UUID player) {
        Entry e = entries.get(player);
        if (e != null) {
            Instant now = clock.instant();
            endVisit(e, now);
            e.lastSeen = now;
        }
    }

    /** Ends every running visit (the node is stopping). */
    public synchronized void closeAll() {
        Instant now = clock.instant();
        for (Entry e : entries.values()) {
            if (e.sessionMode != null) {
                endVisit(e, now);
                e.lastSeen = now;
            }
        }
    }

    /**
     * The access: everything stored about the player.
     *
     * @param player player id
     * @return the record, or empty if the player did not agree
     */
    public synchronized Optional<TrackedPlayer> find(UUID player) {
        Entry e = entries.get(player);
        Instant now = clock.instant();
        if (e == null || due(e, now)) {
            return Optional.empty();
        }
        return Optional.of(view(player, e, now));
    }

    /**
     * Number of players with a record.
     *
     * @return the count
     */
    public synchronized int size() {
        return entries.size();
    }

    /**
     * Deletes records whose last visit is older than the retention period and rewrites the file.
     *
     * @return how many records were deleted
     * @throws IOException if the file cannot be written
     */
    public synchronized int purge() throws IOException {
        Instant now = clock.instant();
        int before = entries.size();
        entries.values().removeIf(e -> due(e, now));
        int removed = before - entries.size();
        if (removed > 0) {
            write();
        }
        return removed;
    }

    /**
     * Writes the records (not the running visits) to the file.
     *
     * @throws IOException if the file cannot be written
     */
    public synchronized void save() throws IOException {
        write();
    }

    private boolean due(Entry e, Instant now) {
        // A player who is online right now is never due.
        Instant seen = Objects.requireNonNull(e.lastSeen, "lastSeen");
        return e.sessionMode == null && !seen.plus(Duration.ofDays(retentionDays)).isAfter(now);
    }

    private static void checkName(String name) {
        if (!TrackedPlayer.NAME.matcher(Objects.requireNonNull(name, "name")).matches()) {
            throw new IllegalArgumentException("invalid name");
        }
    }

    private static void count(Entry e, String mode) {
        e.logins++;
        if (e.modes.containsKey(mode) || e.modes.size() < MAX_MODES) {
            e.modes.computeIfAbsent(mode, k -> new long[2])[0]++;
        }
    }

    private static void endVisit(Entry e, Instant now) {
        if (e.sessionMode == null) {
            return;
        }
        long seconds = Math.max(0, Duration.between(e.sessionSince, now).toSeconds());
        e.playtime += seconds;
        long[] mode = e.modes.get(e.sessionMode);
        if (mode != null) {
            mode[1] += seconds;
        }
        e.sessionMode = null;
        e.sessionSince = null;
    }

    private static TrackedPlayer view(UUID uuid, Entry e, Instant now) {
        long running = e.sessionMode == null ? 0 : Math.max(0, Duration.between(e.sessionSince, now).toSeconds());
        List<TrackedPlayer.ModeTotals> modes = new ArrayList<>();
        e.modes.forEach((mode, v) -> modes.add(new TrackedPlayer.ModeTotals(mode, v[0],
                v[1] + (mode.equals(e.sessionMode) ? running : 0))));
        modes.sort(Comparator.comparing(TrackedPlayer.ModeTotals::mode));
        return new TrackedPlayer(uuid, Objects.requireNonNull(e.name, "name"),
                Objects.requireNonNull(e.consentAt, "consentAt"), e.noticeVersion,
                Objects.requireNonNull(e.firstSeen, "firstSeen"), Objects.requireNonNull(e.lastSeen, "lastSeen"), e.logins,
                e.playtime + running, modes);
    }

    private void write() throws IOException {
        Instant now = clock.instant();
        List<TrackedPlayer> list = new ArrayList<>();
        entries.forEach((uuid, e) -> list.add(view(uuid, e, now)));
        new AtomicFileWriter().write(file, encode(list));
    }

    /**
     * Encodes one record, for the access answer.
     *
     * @param player the record
     * @return JSON bytes
     */
    public static byte[] encodeOne(TrackedPlayer player) {
        return JSON.writeValueAsBytes(node(player));
    }

    static byte[] encode(List<TrackedPlayer> list) {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", VERSION);
        ArrayNode players = root.putArray("players");
        list.forEach(p -> players.add(node(p)));
        return JSON.writeValueAsBytes(root);
    }

    private static ObjectNode node(TrackedPlayer p) {
        ObjectNode node = JSON.createObjectNode();
        node.put("uuid", p.uuid().toString());
        node.put("name", p.name());
        node.put("consent-at", p.consentAt().getEpochSecond());
        node.put("notice-version", p.noticeVersion());
        node.put("first-seen", p.firstSeen().getEpochSecond());
        node.put("last-seen", p.lastSeen().getEpochSecond());
        node.put("logins", p.logins());
        node.put("playtime-seconds", p.playtimeSeconds());
        ArrayNode modes = node.putArray("modes");
        for (TrackedPlayer.ModeTotals m : p.modes()) {
            ObjectNode mode = modes.addObject();
            mode.put("mode", m.mode());
            mode.put("logins", m.logins());
            mode.put("playtime-seconds", m.playtimeSeconds());
        }
        return node;
    }

    /**
     * Decodes a tracking file strictly: unknown versions or fields, mistyped or negative values,
     * invalid ids, names or modes, duplicates and too many players or modes are errors.
     *
     * @param content JSON bytes
     * @return the records
     * @throws FormatException if the content is not valid
     */
    public static List<TrackedPlayer> decode(byte[] content) throws FormatException {
        try {
            if (content.length > MAX_BYTES) {
                throw new IllegalArgumentException("file too large");
            }
            JsonNode root = JSON.readTree(content);
            if (root == null || !root.isObject() || root.size() != 2 || root.path("version").asInt(-1) != VERSION
                    || !root.path("players").isArray() || root.path("players").size() > MAX_PLAYERS) {
                throw new IllegalArgumentException("not a tracking file");
            }
            Map<UUID, TrackedPlayer> result = new LinkedHashMap<>();
            for (JsonNode node : root.get("players")) {
                TrackedPlayer p = player(node);
                if (result.put(p.uuid(), p) != null) {
                    throw new IllegalArgumentException("duplicate player");
                }
            }
            return new ArrayList<>(result.values());
        } catch (RuntimeException e) {
            throw new FormatException("invalid tracking file", e);
        }
    }

    private static TrackedPlayer player(JsonNode node) {
        if (!node.isObject() || node.size() != 9 || !node.path("uuid").isString() || !node.path("name").isString()
                || !integral(node, "consent-at") || !integral(node, "notice-version") || !integral(node, "first-seen")
                || !integral(node, "last-seen") || !integral(node, "logins") || !integral(node, "playtime-seconds")
                || !node.path("modes").isArray() || node.path("modes").size() > MAX_MODES
                || !node.path("notice-version").canConvertToInt()) {
            throw new IllegalArgumentException("invalid entry");
        }
        Map<String, TrackedPlayer.ModeTotals> modes = new LinkedHashMap<>();
        for (JsonNode m : node.get("modes")) {
            if (!m.isObject() || m.size() != 3 || !m.path("mode").isString() || !integral(m, "logins")
                    || !integral(m, "playtime-seconds")) {
                throw new IllegalArgumentException("invalid mode");
            }
            TrackedPlayer.ModeTotals totals = new TrackedPlayer.ModeTotals(m.get("mode").asString(),
                    m.get("logins").asLong(), m.get("playtime-seconds").asLong());
            if (modes.put(totals.mode(), totals) != null) {
                throw new IllegalArgumentException("duplicate mode");
            }
        }
        return new TrackedPlayer(UUID.fromString(node.get("uuid").asString()), node.get("name").asString(),
                Instant.ofEpochSecond(node.get("consent-at").asLong()), node.get("notice-version").asInt(),
                Instant.ofEpochSecond(node.get("first-seen").asLong()),
                Instant.ofEpochSecond(node.get("last-seen").asLong()), node.get("logins").asLong(),
                node.get("playtime-seconds").asLong(), List.copyOf(modes.values()));
    }

    private static boolean integral(JsonNode node, String field) {
        return node.path(field).isIntegralNumber();
    }
}
