// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config.transfer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.ConfigChanged;
import net.vectolyn.cloud.config.ConfigPreview;
import net.vectolyn.cloud.config.ConfigService;
import net.vectolyn.cloud.config.error.ConfigError;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.error.ErrorCode;
import net.vectolyn.cloud.config.io.AtomicFileWriter;
import net.vectolyn.cloud.config.io.ConfigFormat;
import net.vectolyn.cloud.config.io.ConfigMappers;
import net.vectolyn.cloud.config.io.ConfigStore;
import net.vectolyn.cloud.config.io.ConfigYaml;
import net.vectolyn.cloud.config.io.RegularPaths;
import net.vectolyn.cloud.config.io.UnifiedDiff;
import net.vectolyn.cloud.config.model.CloudConfig;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.GroupConfig;
import net.vectolyn.cloud.config.model.NodesConfig;
import net.vectolyn.cloud.config.model.TaskConfig;
import net.vectolyn.cloud.config.operation.ReplaceConfig;
import net.vectolyn.cloud.config.validation.ConfigNames;

/**
 * Export and import of the whole configuration as one zip archive, to move a network to another
 * machine, keep a known state or review a change before it goes live.
 *
 * <p>The archive holds {@code cloud.yml}, {@code nodes.yml}, {@code groups/*.yml},
 * {@code tasks/*.yml} and the other files registered as {@link ExtraFile} (on a node
 * {@code monitor.yml} and {@code backup.yml}), with the names they have in {@code config/}.
 * Never secrets: nothing from {@code secrets/}, no {@code cluster.yml} (the cluster's keys and
 * members belong to one machine), no {@code software.lock.yml}.
 *
 * <p>An import is checked as a whole before anything is written: only the names above are
 * accepted (so an entry can never point outside {@code config/}), each file is read as strictly as
 * a hand-edited one, the result goes through the validation of {@link ConfigService#preview},
 * and the extra files through their own checks. {@link #read} gives the plan with the diff of every
 * file; {@link #apply} writes it through {@link ConfigService#apply}. Groups and tasks that the
 * archive does not have are deleted; an extra file missing from the archive stays as it is.
 */
public final class ConfigArchive {

    /** Upper limit of entries in an archive. */
    public static final int MAX_ENTRIES = 1024;
    /** Upper limit of the archive file and of its unpacked content, 32 MiB. */
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    /** Name of the archive in errors about the archive as a whole. */
    public static final String ARCHIVE = "archive";

    /** Fixed time stamp of every entry, so the same configuration gives the same archive. */
    private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(2020, 1, 1, 0, 0);
    private static final String ALLOWED_CORE = "cloud.yml, nodes.yml, groups/<name>.yml, tasks/<name>.yml";

    /** Checks the content of an extra file; throws with every problem found. */
    @FunctionalInterface
    public interface Checker {

        /**
         * Checks the content.
         *
         * @param content raw bytes (UTF-8)
         * @throws ConfigException with every problem found
         */
        void check(byte[] content) throws ConfigException;
    }

    /**
     * A file in {@code config/} beside the files of {@link ConfigService}, for example
     * {@code monitor.yml}.
     *
     * @param name    file name in {@code config/}, a plain {@code .yml} name
     * @param checker checks an imported file before anything is written
     */
    public record ExtraFile(String name, Checker checker) {

        /** Checks the name. */
        public ExtraFile {
            Objects.requireNonNull(checker, "checker");
            if (name == null || !name.matches("[a-z][a-z0-9.-]{0,30}\\.yml") || name.equals(CloudHome.CLOUD_FILE)
                    || name.equals(CloudHome.NODES_FILE)) {
                throw new IllegalArgumentException("not a plain extra configuration file name");
            }
        }
    }

    /**
     * A checked import, ready for {@link #apply}.
     *
     * @param snapshot the configuration of the archive
     * @param extras   the extra files of the archive, by name
     * @param changes  every file that would change, with its diff, in file name order
     */
    public record Plan(ConfigSnapshot snapshot, Map<String, byte[]> extras, List<ConfigPreview.FileChange> changes) {

        /** Copies the collections. */
        public Plan {
            Objects.requireNonNull(snapshot, "snapshot");
            extras = Map.copyOf(extras);
            changes = List.copyOf(changes);
        }

        /** Whether the import would leave every file as it is. */
        public boolean isEmpty() {
            return changes.isEmpty();
        }

        /** The diffs of every file, one after the other. */
        public String diff() {
            StringBuilder out = new StringBuilder();
            changes.forEach(change -> out.append(change.diff()));
            return out.toString();
        }
    }

    private final ConfigService config;
    private final Map<String, ExtraFile> extras = new LinkedHashMap<>();
    private final AtomicFileWriter writer;

    /**
     * An archive tool for a configuration.
     *
     * @param config the configuration
     * @param extras the other files of {@code config/} that belong in the archive
     */
    public ConfigArchive(ConfigService config, List<ExtraFile> extras) {
        this(config, extras, new AtomicFileWriter());
    }

    /** With a custom writer (tests). */
    ConfigArchive(ConfigService config, List<ExtraFile> extras, AtomicFileWriter writer) {
        this.config = Objects.requireNonNull(config, "config");
        this.writer = Objects.requireNonNull(writer, "writer");
        for (ExtraFile extra : extras) {
            if (this.extras.put(extra.name(), extra) != null) {
                throw new IllegalArgumentException("extra file named twice: " + extra.name());
            }
        }
    }

    /**
     * Writes the current configuration to a new zip file.
     *
     * @param target the archive; must not exist yet
     * @return the names of the entries
     * @throws FileAlreadyExistsException if {@code target} exists
     * @throws IOException                if an extra file cannot be read or the archive cannot be written
     */
    public List<String> export(Path target) throws IOException {
        Path file = target.toAbsolutePath().normalize();
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(file.toString());
        }
        Map<String, byte[]> entries = exportEntries();
        writer.write(file, zip(entries));
        return List.copyOf(entries.keySet());
    }

    /**
     * The entries of an export of the current configuration, in archive order.
     *
     * @return entry name to content
     * @throws IOException if an extra file cannot be read
     */
    public Map<String, byte[]> exportEntries() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        ConfigStore.files(config.snapshot()).forEach((name, value) -> entries.put(name, ConfigYaml.write(name, value)));
        for (String name : extras.keySet()) {
            byte[] content = readExtra(name);
            if (content != null) {
                entries.put(name, content);
            }
        }
        return entries;
    }

    /**
     * Reads and checks an archive file; see {@link #read(byte[])}.
     *
     * @param archive the zip file
     * @return the plan
     * @throws ConfigException if the archive or a file in it is invalid
     * @throws IOException     if the file cannot be read
     */
    public Plan read(Path archive) throws ConfigException, IOException {
        RegularPaths.Kind kind = RegularPaths.kind(archive);
        if (kind != RegularPaths.Kind.FILE) {
            throw new ConfigException(ConfigError.of(ARCHIVE, "", kind == RegularPaths.Kind.MISSING
                    ? ErrorCode.FILE_MISSING : ErrorCode.NOT_REGULAR));
        }
        if (Files.size(archive) > MAX_BYTES) {
            throw new ConfigException(ConfigError.of(ARCHIVE, "", ErrorCode.FILE_TOO_LARGE, MAX_BYTES));
        }
        byte[] content;
        try (InputStream in = Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS)) {
            content = in.readNBytes(MAX_BYTES + 1);
        }
        return read(content);
    }

    /**
     * Unpacks and checks an archive without writing anything: every entry name, every file, the
     * configuration as a whole (also, on a cluster member, that it leaves the head's files alone)
     * and the extra files.
     *
     * @param archive the zip content
     * @return the plan with the diff of every file that would change
     * @throws ConfigException with every problem found
     * @throws IOException     if an extra file on disk cannot be read for the diff
     */
    public Plan read(byte[] archive) throws ConfigException, IOException {
        if (archive.length > MAX_BYTES) {
            throw new ConfigException(ConfigError.of(ARCHIVE, "", ErrorCode.FILE_TOO_LARGE, MAX_BYTES));
        }
        Map<String, byte[]> files = unpack(archive);
        List<ConfigError> errors = new ArrayList<>();
        CloudConfig cloud = parse(files, CloudHome.CLOUD_FILE, ConfigFormat.CLOUD, errors);
        NodesConfig nodes = parse(files, CloudHome.NODES_FILE, ConfigFormat.NODES, errors);
        Map<String, GroupConfig> groups = new TreeMap<>();
        Map<String, TaskConfig> tasks = new TreeMap<>();
        Map<String, byte[]> extraContent = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String name = entry.getKey();
            if (name.startsWith(CloudHome.GROUPS_DIR + "/")) {
                GroupConfig group = parse(files, name, ConfigFormat.GROUP, errors);
                if (group != null) {
                    groups.put(baseName(name), group);
                }
            } else if (name.startsWith(CloudHome.TASKS_DIR + "/")) {
                TaskConfig task = parse(files, name, ConfigFormat.TASK, errors);
                if (task != null) {
                    tasks.put(baseName(name), task);
                }
            } else if (extras.containsKey(name)) {
                // The registered file, not the entry name, names the file from here on.
                ExtraFile extra = extras.get(name);
                try {
                    extra.checker().check(entry.getValue());
                    extraContent.put(extra.name(), entry.getValue());
                } catch (ConfigException e) {
                    errors.addAll(e.errors());
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ConfigException(errors);
        }
        ConfigSnapshot snapshot = ConfigSnapshot.of(cloud, nodes, groups, tasks);
        ConfigPreview preview = config.preview(new ReplaceConfig(snapshot));
        List<ConfigPreview.FileChange> changes = new ArrayList<>(preview.files());
        for (Map.Entry<String, byte[]> entry : extraContent.entrySet()) {
            byte[] current = readExtra(entry.getKey());
            if (current != null && Arrays.equals(current, entry.getValue())) {
                continue;
            }
            String before = current == null ? "" : new String(current, StandardCharsets.UTF_8);
            String after = new String(entry.getValue(), StandardCharsets.UTF_8);
            changes.add(new ConfigPreview.FileChange(entry.getKey(),
                    current == null ? ConfigPreview.Kind.ADDED : ConfigPreview.Kind.CHANGED,
                    UnifiedDiff.diff(entry.getKey(), before, after)));
        }
        changes.sort((a, b) -> a.file().compareTo(b.file()));
        return new Plan(snapshot, extraContent, changes);
    }

    /**
     * Writes a checked import: the configuration through {@link ConfigService#apply} (validated
     * again against the configuration of that moment), then each extra file that differs,
     * atomically.
     *
     * @param plan the result of {@link #read}
     * @return the files written or deleted, relative to {@code config/}
     * @throws ConfigException if the configuration no longer accepts the import; nothing is written then
     * @throws IOException     if a file cannot be written
     */
    public Set<String> apply(Plan plan) throws ConfigException, IOException {
        ConfigChanged change = config.apply(new ReplaceConfig(plan.snapshot()));
        Set<String> written = new LinkedHashSet<>(change.files());
        // Only the registered names are resolved; a name from the archive never reaches a path.
        for (ExtraFile extra : extras.values()) {
            byte[] content = plan.extras().get(extra.name());
            if (content == null) {
                continue;
            }
            byte[] current = readExtra(extra.name());
            if (current == null || !Arrays.equals(current, content)) {
                writer.write(config.home().configDir().resolve(extra.name()), content);
                written.add(extra.name());
            }
        }
        return written;
    }

    /** The bytes of an extra file in {@code config/}, or null if it does not exist. */
    private byte[] readExtra(String name) throws IOException {
        Path path = config.home().configDir().resolve(name);
        RegularPaths.Kind kind = RegularPaths.kind(path);
        if (kind == RegularPaths.Kind.MISSING) {
            return null;
        }
        if (kind != RegularPaths.Kind.FILE) {
            throw new IOException("config/" + name + " is not a regular file");
        }
        if (Files.size(path) > ConfigMappers.MAX_FILE_BYTES) {
            throw new IOException("config/" + name + " is larger than " + ConfigMappers.MAX_FILE_BYTES + " bytes");
        }
        return Files.readAllBytes(path);
    }

    /**
     * Every file entry of the archive by name. Directory entries {@code groups/} and {@code tasks/}
     * are skipped; any other name, a name twice, too many entries or too much content fails.
     */
    private Map<String, byte[]> unpack(byte[] archive) throws ConfigException {
        Map<String, byte[]> files = new TreeMap<>();
        List<ConfigError> errors = new ArrayList<>();
        int count = 0;
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) {
                    throw tooLarge();
                }
                String name = entry.getName();
                if (entry.isDirectory()) {
                    if (!name.equals(CloudHome.GROUPS_DIR + "/") && !name.equals(CloudHome.TASKS_DIR + "/")) {
                        errors.add(notInArchive(name));
                    }
                    continue;
                }
                if (!allowed(name)) {
                    errors.add(notInArchive(name));
                    continue;
                }
                byte[] content = zip.readNBytes(ConfigMappers.MAX_FILE_BYTES + 1);
                if (content.length > ConfigMappers.MAX_FILE_BYTES) {
                    throw new ConfigException(ConfigError.of(name, "", ErrorCode.FILE_TOO_LARGE,
                            ConfigMappers.MAX_FILE_BYTES));
                }
                total += content.length;
                if (total > MAX_BYTES) {
                    throw tooLarge();
                }
                if (files.put(name, content) != null) {
                    errors.add(ConfigError.of(ARCHIVE, "", ErrorCode.DUPLICATE_ENTRY, name));
                }
            }
        } catch (ZipException e) {
            throw new ConfigException(ConfigError.of(ARCHIVE, "", ErrorCode.SYNTAX));
        } catch (IOException e) {
            throw new ConfigException(ConfigError.of(ARCHIVE, "", ErrorCode.FILE_ACCESS));
        } catch (IllegalArgumentException e) {
            // A name that is not valid in the archive's encoding.
            throw new ConfigException(ConfigError.of(ARCHIVE, "", ErrorCode.SYNTAX));
        }
        if (count == 0) {
            errors.add(ConfigError.of(ARCHIVE, "", ErrorCode.EMPTY));
        }
        if (!errors.isEmpty()) {
            throw new ConfigException(errors);
        }
        return files;
    }

    private static ConfigException tooLarge() {
        return new ConfigException(ConfigError.of(ARCHIVE, "", ErrorCode.ARCHIVE_TOO_LARGE, MAX_ENTRIES, MAX_BYTES));
    }

    private ConfigError notInArchive(String name) {
        StringBuilder allowedNames = new StringBuilder(ALLOWED_CORE);
        extras.keySet().forEach(extra -> allowedNames.append(", ").append(extra));
        return ConfigError.of(ConfigNames.safeArgument(name), "", ErrorCode.NOT_IN_ARCHIVE, allowedNames.toString());
    }

    /** Whether an entry name is one of the files an archive may hold; nothing else is ever resolved. */
    private boolean allowed(String name) {
        if (name.equals(CloudHome.CLOUD_FILE) || name.equals(CloudHome.NODES_FILE) || extras.containsKey(name)) {
            return true;
        }
        String[] parts = name.split("/", -1);
        return parts.length == 2
                && (parts[0].equals(CloudHome.GROUPS_DIR) || parts[0].equals(CloudHome.TASKS_DIR))
                && parts[1].endsWith(CloudHome.EXTENSION)
                && ConfigNames.isValidName(parts[1].substring(0, parts[1].length() - CloudHome.EXTENSION.length()));
    }

    private static String baseName(String name) {
        return name.substring(name.indexOf('/') + 1, name.length() - CloudHome.EXTENSION.length());
    }

    private static <T> T parse(Map<String, byte[]> files, String name, ConfigFormat<T> format, List<ConfigError> errors) {
        byte[] content = files.get(name);
        if (content == null) {
            errors.add(ConfigError.of(name, "", ErrorCode.FILE_MISSING));
            return null;
        }
        try {
            return ConfigYaml.read(name, content, format).value();
        } catch (ConfigException e) {
            errors.addAll(e.errors());
            return null;
        }
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTimeLocal(ENTRY_TIME);
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
