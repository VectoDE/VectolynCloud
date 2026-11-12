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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.vectolyn.cloud.config.io.AtomicFileWriter;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The IP ban list in {@code state/ip-bans.json}. An entry exists for as long as the ban is needed
 * to protect the network and never longer than the retention period of
 * {@link PrivacyConfig.IpBans}: {@link #purge()} deletes expired entries and entries older than the
 * retention period, and runs when the store opens and on a schedule ({@link PrivacyRuntime}).
 * Writes are atomic. All methods are thread safe.
 */
public final class IpBanStore {

    /** Most entries in one file. */
    public static final int MAX_ENTRIES = 100_000;

    /** Largest file read: 32 MiB. */
    public static final int MAX_BYTES = 32 * 1024 * 1024;

    private static final int VERSION = 1;
    private static final ObjectMapper JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final Path file;
    private final int retentionDays;
    private final Clock clock;
    private final Map<String, IpBan> bans = new LinkedHashMap<>();

    /** The file is not a valid ban list. */
    public static final class FormatException extends Exception {
        private static final long serialVersionUID = 1L;

        FormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private IpBanStore(Path file, int retentionDays, Clock clock) {
        this.file = file;
        this.retentionDays = retentionDays;
        this.clock = clock;
    }

    /**
     * Opens the list and deletes what is due.
     *
     * @param file          the list file; a missing file is an empty list
     * @param retentionDays retention period in days
     * @param clock         time source
     * @return the store
     * @throws IOException     if the file cannot be read or written
     * @throws FormatException if the file is not a valid list
     */
    public static IpBanStore open(Path file, int retentionDays, Clock clock) throws IOException, FormatException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(clock, "clock");
        if (retentionDays < 1) {
            throw new IllegalArgumentException("retentionDays must be at least 1");
        }
        IpBanStore store = new IpBanStore(file, retentionDays, clock);
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            for (IpBan ban : decode(in.readNBytes(MAX_BYTES + 1))) {
                store.bans.put(ban.address(), ban);
            }
        } catch (NoSuchFileException e) {
            // no list yet
        }
        store.purge();
        return store;
    }

    /**
     * Writes a ban. An address that is already banned is renewed: the retention period starts again.
     *
     * @param address  IP address literal
     * @param reason   reason, see {@link IpBan#reason()}
     * @param duration how long the ban lasts, or null until the retention period ends
     * @return the entry
     * @throws IOException if the list cannot be written
     */
    public synchronized IpBan ban(String address, String reason, Duration duration) throws IOException {
        Instant now = clock.instant();
        IpBan ban = new IpBan(address, reason, now, duration == null ? null : now.plus(duration));
        if (bans.size() >= MAX_ENTRIES && !bans.containsKey(ban.address())) {
            throw new IllegalStateException("ban list is full");
        }
        bans.put(ban.address(), ban);
        write();
        return ban;
    }

    /**
     * Lifts a ban and deletes the entry.
     *
     * @param address IP address literal
     * @return whether there was an entry
     * @throws IOException if the list cannot be written
     */
    public synchronized boolean unban(String address) throws IOException {
        boolean removed = bans.remove(IpAddresses.normalize(address)) != null;
        if (removed) {
            write();
        }
        return removed;
    }

    /**
     * The active ban of an address, if any. An entry that is due counts as gone even before the
     * next {@link #purge()}.
     *
     * @param address IP address literal
     * @return the ban
     */
    public synchronized Optional<IpBan> find(String address) {
        IpBan ban = bans.get(IpAddresses.normalize(address));
        return ban == null || ban.deletable(clock.instant(), retentionDays) ? Optional.empty() : Optional.of(ban);
    }

    /**
     * Whether the address is banned now.
     *
     * @param address IP address literal
     * @return true if there is an active ban
     */
    public boolean isBanned(String address) {
        return find(address).isPresent();
    }

    /**
     * The active bans.
     *
     * @return a copy of the list
     */
    public synchronized List<IpBan> list() {
        Instant now = clock.instant();
        return bans.values().stream().filter(b -> !b.deletable(now, retentionDays)).toList();
    }

    /**
     * Deletes expired entries and entries older than the retention period, and rewrites the file.
     *
     * @return how many entries were deleted
     * @throws IOException if the list cannot be written
     */
    public synchronized int purge() throws IOException {
        Instant now = clock.instant();
        int before = bans.size();
        bans.values().removeIf(b -> b.deletable(now, retentionDays));
        int removed = before - bans.size();
        if (removed > 0) {
            write();
        }
        return removed;
    }

    private void write() throws IOException {
        new AtomicFileWriter().write(file, encode(List.copyOf(bans.values())));
    }

    /**
     * Encodes a list.
     *
     * @param list the entries
     * @return JSON bytes
     * @throws IOException if JSON cannot be written
     */
    static byte[] encode(List<IpBan> list) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", VERSION);
        ArrayNode array = root.putArray("bans");
        for (IpBan ban : list) {
            ObjectNode node = array.addObject();
            node.put("address", ban.address());
            node.put("reason", ban.reason());
            node.put("created", ban.createdAt().getEpochSecond());
            if (ban.expiresAt() != null) {
                node.put("expires", ban.expiresAt().getEpochSecond());
            }
        }
        return JSON.writeValueAsBytes(root);
    }

    /**
     * Decodes a list strictly: unknown versions, missing or mistyped fields, invalid addresses,
     * duplicates, too many entries and trailing content are errors.
     *
     * @param content JSON bytes
     * @return the entries
     * @throws FormatException if the content is not a valid list
     */
    public static List<IpBan> decode(byte[] content) throws FormatException {
        try {
            if (content.length > MAX_BYTES) {
                throw new IllegalArgumentException("file too large");
            }
            JsonNode root = JSON.readTree(content);
            if (root == null || !root.isObject() || root.size() != 2 || root.path("version").asInt(-1) != VERSION
                    || !root.path("bans").isArray() || root.path("bans").size() > MAX_ENTRIES) {
                throw new IllegalArgumentException("not a ban list");
            }
            Map<String, IpBan> result = new LinkedHashMap<>();
            for (JsonNode node : root.get("bans")) {
                boolean expires = node.has("expires");
                if (!node.isObject() || !node.path("address").isString() || !node.path("reason").isString()
                        || !node.path("created").isIntegralNumber()
                        || (expires && !node.get("expires").isIntegralNumber())
                        || node.size() != (expires ? 4 : 3)) {
                    throw new IllegalArgumentException("invalid entry");
                }
                IpBan ban = new IpBan(node.get("address").asString(), node.get("reason").asString(),
                        Instant.ofEpochSecond(node.get("created").asLong()),
                        expires ? Instant.ofEpochSecond(node.get("expires").asLong()) : null);
                if (result.put(ban.address(), ban) != null) {
                    throw new IllegalArgumentException("duplicate address");
                }
            }
            return new ArrayList<>(result.values());
        } catch (RuntimeException e) {
            throw new FormatException("invalid ban list", e);
        }
    }
}
