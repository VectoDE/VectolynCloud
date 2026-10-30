package net.vectolyn.cloud.monitor.history;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.vectolyn.cloud.config.io.AtomicFileWriter;
import net.vectolyn.cloud.monitor.metric.NodeSample;
import net.vectolyn.cloud.monitor.metric.ServiceSample;
import net.vectolyn.protocol.ServiceId;

/**
 * Keeps the history across restarts in {@code <home>/state/monitor-history.bin} (format in
 * {@link HistoryFile}). Optional: only used with {@code history.persist: true}.
 *
 * <ul>
 *   <li>Written atomically (temporary file, flush, rename), so a crash leaves the old file.</li>
 *   <li>Samples older than the retention are dropped when writing and when reading; the number
 *       per series is capped by {@code history.samples}.</li>
 *   <li>A damaged file is moved aside as {@code monitor-history.bin.broken-<millis>} and the
 *       monitor starts with an empty history. A symbolic link or other non-regular file is not
 *       read and not replaced.</li>
 * </ul>
 */
public final class HistoryStore {

    /** File name in {@code state/}. */
    public static final String FILE_NAME = "monitor-history.bin";

    private static final Logger LOG = Logger.getLogger(HistoryStore.class.getName());

    private final Path file;
    private final Duration retention;
    private final int samples;
    private final AtomicFileWriter writer = new AtomicFileWriter();

    /**
     * Creates the store.
     *
     * @param home      cloud home
     * @param retention how long samples are kept
     * @param samples   most samples per series
     */
    public HistoryStore(Path home, Duration retention, int samples) {
        this.file = Objects.requireNonNull(home, "home").resolve("state").resolve(FILE_NAME).toAbsolutePath().normalize();
        this.retention = Objects.requireNonNull(retention, "retention");
        if (samples < 1) {
            throw new IllegalArgumentException("samples must be at least 1");
        }
        this.samples = samples;
    }

    /** The file. */
    public Path file() {
        return file;
    }

    /**
     * Reads the file. A damaged file is moved aside and logged; a missing one gives empty.
     *
     * @param now the time, for the retention
     * @return the history, or empty if there is none (or it was damaged)
     */
    public Optional<HistoryFile.Content> load(Instant now) {
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                LOG.warning("state/" + FILE_NAME + " is not a regular file; the history starts empty and is not saved over it.");
                return Optional.empty();
            }
            byte[] content;
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                content = in.readNBytes(HistoryFile.MAX_BYTES + 1);
            }
            return Optional.of(trim(HistoryFile.decode(content), now));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (HistoryFile.FormatException e) {
            Path aside = file.resolveSibling(FILE_NAME + ".broken-" + now.toEpochMilli());
            try {
                Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
                LOG.warning("state/" + FILE_NAME + " is damaged (" + e.getMessage() + "); moved to "
                        + aside.getFileName() + ", the history starts empty.");
            } catch (IOException move) {
                LOG.warning("state/" + FILE_NAME + " is damaged (" + e.getMessage() + ") and could not be moved aside.");
            }
            return Optional.empty();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "state/" + FILE_NAME + " could not be read; the history starts empty.", e);
            return Optional.empty();
        }
    }

    /**
     * Writes the file atomically, creating {@code state/} if needed. Does nothing if the file
     * exists but is not a regular file.
     *
     * @param content the history
     * @param now     the time, for the retention
     * @throws IOException if writing fails
     */
    public void save(HistoryFile.Content content, Instant now) throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.createDirectories(file.getParent());
        writer.write(file, HistoryFile.encode(trim(content, now)));
    }

    /** Drops samples older than the retention and caps each series. */
    HistoryFile.Content trim(HistoryFile.Content content, Instant now) {
        Instant oldest = now.minus(retention);
        List<NodeSample> node = cap(content.node().stream().filter(s -> !s.at().isBefore(oldest)).toList());
        Map<ServiceId, List<ServiceSample>> services = new LinkedHashMap<>();
        content.services().forEach((id, list) -> {
            List<ServiceSample> kept = cap(list.stream().filter(s -> !s.at().isBefore(oldest)).toList());
            if (!kept.isEmpty()) {
                services.put(id, kept);
            }
        });
        return new HistoryFile.Content(node, services);
    }

    private <T> List<T> cap(List<T> list) {
        return list.size() <= samples ? list : list.subList(list.size() - samples, list.size());
    }
}
