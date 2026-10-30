package net.vectolyn.cloud.config.io;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.error.ConfigError;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.error.ErrorCode;
import net.vectolyn.cloud.config.model.CloudConfig;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.GroupConfig;
import net.vectolyn.cloud.config.model.NodesConfig;
import net.vectolyn.cloud.config.model.TaskConfig;
import net.vectolyn.cloud.config.validation.ConfigNames;

/**
 * Reads the whole configuration from a {@link CloudHome} and writes the files that changed.
 * Does not validate; that is {@link net.vectolyn.cloud.config.validation.ConfigValidator}'s job.
 */
public final class ConfigStore {

    private final CloudHome home;
    private final AtomicFileWriter writer;

    /** Store for {@code home} with the default atomic writer. */
    public ConfigStore(CloudHome home) {
        this(home, new AtomicFileWriter());
    }

    /** Store with a custom writer (tests). */
    public ConfigStore(CloudHome home, AtomicFileWriter writer) {
        this.home = Objects.requireNonNull(home, "home");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    /** The home this store works on. */
    public CloudHome home() {
        return home;
    }

    /**
     * Result of {@link #load()}.
     *
     * @param snapshot     the configuration; defaults for a missing {@code cloud.yml} or {@code nodes.yml}
     * @param missingFiles files that do not exist yet and were filled with defaults
     * @param migrated     files with an older {@code schema-version}, by file name, with the version found
     */
    public record Loaded(ConfigSnapshot snapshot, Set<String> missingFiles, Map<String, Integer> migrated) {

        /** Copies the collections. */
        public Loaded {
            missingFiles = Set.copyOf(missingFiles);
            migrated = Map.copyOf(migrated);
        }
    }

    /**
     * Reads {@code cloud.yml}, {@code nodes.yml} and every {@code *.yml} in {@code groups/} and
     * {@code tasks/}. Problems in several files are all reported together.
     */
    public Loaded load() throws ConfigException {
        List<ConfigError> errors = new ArrayList<>();
        Set<String> missing = new LinkedHashSet<>();
        Map<String, Integer> migrated = new LinkedHashMap<>();

        CloudConfig cloud = readSingle(CloudHome.CLOUD_FILE, ConfigFormat.CLOUD, errors, missing, migrated);
        NodesConfig nodes = readSingle(CloudHome.NODES_FILE, ConfigFormat.NODES, errors, missing, migrated);
        Map<String, GroupConfig> groups = readFolder(CloudHome.GROUPS_DIR, ConfigFormat.GROUP, errors, migrated);
        Map<String, TaskConfig> tasks = readFolder(CloudHome.TASKS_DIR, ConfigFormat.TASK, errors, migrated);

        if (!errors.isEmpty()) {
            throw new ConfigException(errors);
        }
        if (cloud == null) {
            cloud = CloudConfig.defaults();
        }
        if (nodes == null) {
            nodes = NodesConfig.defaults();
        }
        return new Loaded(ConfigSnapshot.of(cloud, nodes, groups, tasks), missing, migrated);
    }

    /**
     * Writes every file whose content differs between {@code previous} and {@code next}, plus every
     * file named in {@code force}, and deletes group and task files that {@code next} no longer has.
     * Each file is replaced atomically; a change that spans several files is written file by file.
     *
     * @param previous what is on disk now, or null to write every file
     * @return the files written or deleted, relative to the home
     */
    public Set<String> save(ConfigSnapshot previous, ConfigSnapshot next, Set<String> force) throws IOException {
        Map<String, Object> before = previous == null ? Map.of() : files(previous);
        Map<String, Object> after = files(next);
        Set<String> touched = new LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            String file = entry.getKey();
            if (previous == null || force.contains(file) || !entry.getValue().equals(before.get(file))) {
                writer.write(home.resolve(file), ConfigYaml.write(entry.getValue()));
                touched.add(file);
            }
        }
        for (String file : before.keySet()) {
            if (!after.containsKey(file)) {
                Files.deleteIfExists(home.resolve(file));
                touched.add(file);
            }
        }
        return touched;
    }

    /**
     * Reads {@code cloud.yml} alone: on a cluster member the other files come from the head.
     *
     * @return the file's content, migrated to the current format
     * @throws ConfigException if the file is missing, no regular file or invalid
     */
    public CloudConfig loadCloud() throws ConfigException {
        List<ConfigError> errors = new ArrayList<>();
        Set<String> missing = new LinkedHashSet<>();
        CloudConfig cloud = readSingle(CloudHome.CLOUD_FILE, ConfigFormat.CLOUD, errors, missing, new LinkedHashMap<>());
        if (!missing.isEmpty()) {
            errors.add(ConfigError.of(CloudHome.CLOUD_FILE, "", ErrorCode.FILE_MISSING));
        }
        if (!errors.isEmpty() || cloud == null) {
            throw new ConfigException(errors.isEmpty()
                    ? List.of(ConfigError.of(CloudHome.CLOUD_FILE, "", ErrorCode.FILE_ACCESS)) : errors);
        }
        return cloud;
    }

    /**
     * Brings {@code nodes.yml}, {@code groups/} and {@code tasks/} on disk to exactly the content
     * of {@code snapshot}, as a cluster member keeps the files its head mirrors: every file whose
     * bytes differ from {@link ConfigYaml#write} of the snapshot is replaced atomically, and every
     * other {@code .yml} or {@code .yaml} entry in {@code groups/} and {@code tasks/} (also a link
     * named like one) is deleted. {@code cloud.yml} is not touched.
     *
     * @return the files written or deleted, relative to {@code config/}
     * @throws IOException if a file cannot be read, written or deleted
     */
    public Set<String> writeMirrored(ConfigSnapshot snapshot) throws IOException {
        Map<String, Object> wanted = files(snapshot);
        wanted.remove(CloudHome.CLOUD_FILE);
        Set<String> touched = new LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : wanted.entrySet()) {
            Path path = home.resolve(entry.getKey());
            byte[] content = ConfigYaml.write(entry.getValue());
            if (!sameContent(path, content)) {
                writer.write(path, content);
                touched.add(entry.getKey());
            }
        }
        for (String folder : List.of(CloudHome.GROUPS_DIR, CloudHome.TASKS_DIR)) {
            Path dir = home.configDir().resolve(folder);
            if (RegularPaths.kind(dir) != RegularPaths.Kind.DIRECTORY) {
                continue;
            }
            List<Path> entries = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                stream.forEach(entries::add);
            }
            for (Path entry : entries) {
                String fileName = entry.getFileName().toString();
                String relative = folder + "/" + fileName;
                boolean config = fileName.endsWith(".yml") || fileName.endsWith(".yaml");
                if (!config || fileName.startsWith(".") || wanted.containsKey(relative)
                        || RegularPaths.kind(entry) == RegularPaths.Kind.DIRECTORY) {
                    continue;
                }
                Files.deleteIfExists(entry);
                touched.add(relative);
            }
        }
        return touched;
    }

    /** Whether {@code path} is a regular file with exactly {@code content}. */
    private static boolean sameContent(Path path, byte[] content) throws IOException {
        if (RegularPaths.kind(path) != RegularPaths.Kind.FILE || Files.size(path) != content.length) {
            return false;
        }
        return Arrays.equals(Files.readAllBytes(path), content);
    }

    /**
     * Copies {@code file} to {@code <file>.v<version>.bak} before a migrated file is rewritten.
     */
    public void backup(String file, int version) throws IOException {
        Path source = home.resolve(file);
        Path target = source.resolveSibling(source.getFileName() + ".v" + version + ".bak");
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Every file of a snapshot with its model object, keyed by relative name. */
    public static Map<String, Object> files(ConfigSnapshot snapshot) {
        Map<String, Object> files = new TreeMap<>();
        files.put(CloudHome.CLOUD_FILE, snapshot.cloud());
        files.put(CloudHome.NODES_FILE, snapshot.nodes());
        snapshot.groups().forEach((name, group) -> files.put(CloudHome.groupFileName(name), group));
        snapshot.tasks().forEach((name, task) -> files.put(CloudHome.taskFileName(name), task));
        return files;
    }

    private <T> T readSingle(String file, ConfigFormat<T> format, List<ConfigError> errors, Set<String> missing,
                             Map<String, Integer> migrated) {
        Path path = home.configDir().resolve(file);
        RegularPaths.Kind kind;
        try {
            kind = RegularPaths.kind(path);
        } catch (IOException e) {
            errors.add(ConfigError.of(file, "", ErrorCode.FILE_ACCESS));
            return null;
        }
        if (kind == RegularPaths.Kind.MISSING) {
            missing.add(file);
            return null;
        }
        if (kind != RegularPaths.Kind.FILE) {
            errors.add(ConfigError.of(file, "", ErrorCode.NOT_REGULAR));
            return null;
        }
        return readFile(file, path, format, errors, migrated);
    }

    private <T> Map<String, T> readFolder(String folder, ConfigFormat<T> format, List<ConfigError> errors,
                                          Map<String, Integer> migrated) {
        Map<String, T> result = new TreeMap<>();
        Path dir = home.configDir().resolve(folder);
        RegularPaths.Kind dirKind;
        try {
            dirKind = RegularPaths.kind(dir);
        } catch (IOException e) {
            errors.add(ConfigError.of(folder + "/", "", ErrorCode.FILE_ACCESS));
            return result;
        }
        if (dirKind == RegularPaths.Kind.MISSING) {
            return result;
        }
        if (dirKind != RegularPaths.Kind.DIRECTORY) {
            errors.add(ConfigError.of(folder + "/", "", ErrorCode.NOT_REGULAR));
            return result;
        }
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            stream.forEach(entries::add);
        } catch (IOException e) {
            errors.add(ConfigError.of(folder + "/", "", ErrorCode.FILE_ACCESS));
            return result;
        }
        entries.sort(null);
        for (Path entry : entries) {
            String fileName = entry.getFileName().toString();
            if (fileName.startsWith(".") || Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                continue; // hidden files (editor swap files, our own temp files) and folders
            }
            String relative = folder + "/" + fileName;
            boolean yml = fileName.endsWith(".yml");
            if (!yml && !fileName.endsWith(".yaml")) {
                continue; // backups and notes next to the files
            }
            if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                errors.add(ConfigError.of(relative, "", ErrorCode.NOT_REGULAR));
                continue; // a link or special file named like a configuration file
            }
            String name = fileName.substring(0, fileName.lastIndexOf('.'));
            if (!yml || !ConfigNames.isValidName(name)) {
                errors.add(ConfigError.of(relative, "", ErrorCode.INVALID_FILE_NAME, ConfigNames.NAME + "\\.yml"));
                continue;
            }
            T value = readFile(relative, entry, format, errors, migrated);
            if (value != null) {
                result.put(name, value);
            }
        }
        return result;
    }

    private static <T> T readFile(String file, Path path, ConfigFormat<T> format, List<ConfigError> errors,
                                  Map<String, Integer> migrated) {
        try {
            if (Files.size(path) > ConfigMappers.MAX_FILE_BYTES) {
                errors.add(ConfigError.of(file, "", ErrorCode.FILE_TOO_LARGE, ConfigMappers.MAX_FILE_BYTES));
                return null;
            }
            ConfigYaml.Decoded<T> decoded = ConfigYaml.read(file, Files.readAllBytes(path), format);
            if (decoded.fileVersion() < format.currentVersion()) {
                migrated.put(file, decoded.fileVersion());
            }
            return decoded.value();
        } catch (NoSuchFileException e) {
            return null; // deleted between listing and reading
        } catch (IOException e) {
            errors.add(ConfigError.of(file, "", ErrorCode.FILE_ACCESS));
            return null;
        } catch (ConfigException e) {
            errors.addAll(e.errors());
            return null;
        }
    }
}
