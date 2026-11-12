// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel.auth;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.vectolyn.cloud.api.http.ApiJson;
import net.vectolyn.cloud.config.io.RegularPaths;

/**
 * The panel's audit log: one JSON object per line in {@code logs/panel-audit.jsonl}, for every
 * sign-in, sign-out, failed sign-in, setup and change of accounts, factors, roles and rights.
 * Entries hold the time, the event, the acting user, the affected user or role, the client address
 * and plain details; never a password, code, token or key. At {@value #MAX_BYTES} bytes the file
 * moves to {@code panel-audit.1.jsonl} (one old file is kept).
 */
public final class AuditLog {

    /** File name below {@code logs/}. */
    public static final String FILE_NAME = "panel-audit.jsonl";
    /** Size at which the file is rotated. */
    static final long MAX_BYTES = 10L * 1024 * 1024;
    /** Most entries one read returns. */
    public static final int MAX_READ = 500;

    private static final System.Logger LOG = System.getLogger(AuditLog.class.getName());

    private final Path file;
    private final Clock clock;

    /**
     * Creates the log.
     *
     * @param file  the file
     * @param clock the clock
     */
    public AuditLog(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    /**
     * Writes an entry. A failure is logged and does not stop the action.
     *
     * @param event   for example {@code login.ok}
     * @param actor   who acted, or null
     * @param target  whom or what it concerned, or null
     * @param address the client address key, or null
     * @param details further plain values, or an empty map
     */
    public synchronized void record(String event, String actor, String target, String address, Map<String, ?> details) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("time", clock.instant().toString());
        entry.put("event", event);
        if (actor != null) {
            entry.put("actor", actor);
        }
        if (target != null) {
            entry.put("target", target);
        }
        if (address != null) {
            entry.put("address", address);
        }
        if (details != null && !details.isEmpty()) {
            entry.put("details", details);
        }
        byte[] line = (new String(ApiJson.write(entry), StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            RegularPaths.requireDirectoryOrMissing(file.getParent());
            Files.createDirectories(file.getParent());
            RegularPaths.requireFileOrMissing(file);
            if (Files.exists(file) && Files.size(file) + line.length > MAX_BYTES) {
                Files.move(file, file.resolveSibling("panel-audit.1.jsonl"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.write(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "The panel audit log cannot be written ({0}); event {1} is lost.",
                    e.getClass().getSimpleName(), event);
        }
    }

    /**
     * The newest entries, newest first.
     *
     * @param limit how many, at most {@value #MAX_READ}
     * @return the entries as maps
     * @throws IOException if the file cannot be read
     */
    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> newest(int limit) throws IOException {
        int wanted = Math.max(1, Math.min(limit, MAX_READ));
        if (!Files.exists(file)) {
            return List.of();
        }
        RegularPaths.requireFileOrMissing(file);
        List<String> lines = tail(wanted);
        List<Map<String, Object>> entries = new ArrayList<>(lines.size());
        for (String text : lines) {
            try {
                entries.add(ApiJson.mapper().readValue(text, Map.class));
            } catch (RuntimeException e) {
                // a line cut by a crash; skip it
            }
        }
        Collections.reverse(entries);
        return entries;
    }

    private List<String> tail(int count) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            long start = Math.max(0, length - Math.min(length, 2L * 1024 * 1024));
            byte[] buffer = new byte[(int) (length - start)];
            raf.seek(start);
            raf.readFully(buffer);
            String[] all = new String(buffer, StandardCharsets.UTF_8).split("\n");
            int from = start > 0 ? 1 : 0; // the first line may be cut
            List<String> lines = new ArrayList<>();
            for (int i = Math.max(from, all.length - count); i < all.length; i++) {
                if (!all[i].isBlank()) {
                    lines.add(all[i]);
                }
            }
            return lines;
        }
    }
}
