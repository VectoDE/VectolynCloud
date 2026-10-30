package net.vectolyn.cloud.config;

import java.nio.file.Path;
import java.util.Objects;
import net.vectolyn.cloud.config.validation.ConfigNames;

/**
 * File layout of the cloud home, the folder the cloud runs in (never a folder of the repository).
 *
 * <pre>
 * &lt;home&gt;/
 *   config/            cloud.yml, nodes.yml, monitor.yml, backup.yml, software.lock.yml
 *     groups/          &lt;group&gt;.yml
 *     tasks/           &lt;task&gt;.yml
 *   local/
 *     templates/&lt;template&gt;/default/   only for wanted servers
 *     services/&lt;service&gt;/             static services (kept)
 *   temp/services/&lt;service&gt;_&lt;run&gt;/    dynamic services, deleted on stop
 *   database/          managed MariaDB (binaries, data, its configuration)
 *   storage/           downloaded software, JREs, the libraries of VectolynCloud.jar
 *   secrets/           vectolyn.env and the other secrets, owner only
 *   backups/
 *   logs/              cloud.log and logs/services/&lt;service&gt;.log
 *   state/
 * </pre>
 *
 * Configuration file names in messages, events and the API ({@code cloud.yml},
 * {@code groups/lobby.yml}) are relative to {@link #configDir()}, so they read the same in every
 * place. Modules that do not depend on {@code cloud-config} ({@code cloud-download},
 * {@code cloud-launcher}) keep their own copy of the folder names they need; a test of the node
 * checks that they agree with the constants here.
 *
 * @param root the home directory
 */
public record CloudHome(Path root) {

    /** Folder of every configuration file, relative to the home. */
    public static final String CONFIG_DIR = "config";
    /** {@code cloud.yml}, relative to {@link #CONFIG_DIR}. */
    public static final String CLOUD_FILE = "cloud.yml";
    /** {@code nodes.yml}, relative to {@link #CONFIG_DIR}. */
    public static final String NODES_FILE = "nodes.yml";
    /** {@code monitor.yml}, relative to {@link #CONFIG_DIR}. */
    public static final String MONITOR_FILE = "monitor.yml";
    /** {@code backup.yml}, relative to {@link #CONFIG_DIR}. */
    public static final String BACKUP_FILE = "backup.yml";
    /** {@code software.lock.yml}, relative to {@link #CONFIG_DIR}. */
    public static final String SOFTWARE_LOCK_FILE = "software.lock.yml";
    /** Folder of the group files, relative to {@link #CONFIG_DIR}. */
    public static final String GROUPS_DIR = "groups";
    /** Folder of the task files, relative to {@link #CONFIG_DIR}. */
    public static final String TASKS_DIR = "tasks";
    /** Folder of the templates, relative to the home: {@code local/templates/<template>/default/}. */
    public static final String TEMPLATES_DIR = "local/templates";
    /** Name of the template variant below {@code local/templates/<template>/}. */
    public static final String DEFAULT_VARIANT = "default";
    /** Folder of the static services, relative to the home: {@code local/services/<service>/}. */
    public static final String STATIC_SERVICES_DIR = "local/services";
    /** Folder of the dynamic services, relative to the home: {@code temp/services/<service>_<run>/}. */
    public static final String DYNAMIC_SERVICES_DIR = "temp/services";
    /** Temporary files of the cloud, relative to the home. */
    public static final String TEMP_DIR = "temp";
    /** The managed MariaDB, relative to the home. */
    public static final String DATABASE_DIR = "database";
    /** Downloads, JREs and libraries, relative to the home. */
    public static final String STORAGE_DIR = "storage";
    /** Folder of the secrets, relative to the home. */
    public static final String SECRETS_DIR = "secrets";
    /** Secrets file inside {@link #SECRETS_DIR}. */
    public static final String SECRETS_FILE = "vectolyn.env";
    /** Default folder of the backups, relative to the home. */
    public static final String BACKUPS_DIR = "backups";
    /** Folder of the log files, relative to the home. */
    public static final String LOGS_DIR = "logs";
    /** Folder of the state files (lock, service states, history), relative to the home. */
    public static final String STATE_DIR = "state";
    /** Extension of configuration files. */
    public static final String EXTENSION = ".yml";

    /** Normalizes the root to an absolute path. */
    public CloudHome {
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    /** Home at {@code root}. */
    public static CloudHome of(Path root) {
        return new CloudHome(root);
    }

    /** {@code <home>/config}. */
    public Path configDir() {
        return root.resolve(CONFIG_DIR);
    }

    /** {@code <home>/config/cloud.yml}. */
    public Path cloudFile() {
        return configDir().resolve(CLOUD_FILE);
    }

    /** {@code <home>/config/nodes.yml}. */
    public Path nodesFile() {
        return configDir().resolve(NODES_FILE);
    }

    /** {@code <home>/config/monitor.yml}. */
    public Path monitorFile() {
        return configDir().resolve(MONITOR_FILE);
    }

    /** {@code <home>/config/backup.yml}. */
    public Path backupFile() {
        return configDir().resolve(BACKUP_FILE);
    }

    /** {@code <home>/config/software.lock.yml}. */
    public Path softwareLockFile() {
        return configDir().resolve(SOFTWARE_LOCK_FILE);
    }

    /** {@code <home>/config/groups}. */
    public Path groupsDir() {
        return configDir().resolve(GROUPS_DIR);
    }

    /** {@code <home>/config/tasks}. */
    public Path tasksDir() {
        return configDir().resolve(TASKS_DIR);
    }

    /** {@code <home>/local/templates}. */
    public Path templatesDir() {
        return root.resolve(TEMPLATES_DIR);
    }

    /**
     * {@code <home>/local/templates/<template>/default}, the folder a group's services are rendered
     * from.
     *
     * @param template the group's {@code template}, for example {@code Lobby-Server}
     * @return the folder
     * @throws IllegalArgumentException if the name is no plain folder name
     */
    public Path templateDir(String template) {
        return child(templatesDir(), template).resolve(DEFAULT_VARIANT);
    }

    /** {@code <home>/local/services}. */
    public Path staticServicesDir() {
        return root.resolve(STATIC_SERVICES_DIR);
    }

    /** {@code <home>/temp/services}. */
    public Path dynamicServicesDir() {
        return root.resolve(DYNAMIC_SERVICES_DIR);
    }

    /** {@code <home>/temp}. */
    public Path tempDir() {
        return root.resolve(TEMP_DIR);
    }

    /** {@code <home>/database}. */
    public Path databaseDir() {
        return root.resolve(DATABASE_DIR);
    }

    /** {@code <home>/storage}. */
    public Path storageDir() {
        return root.resolve(STORAGE_DIR);
    }

    /** {@code <home>/secrets}. */
    public Path secretsDir() {
        return root.resolve(SECRETS_DIR);
    }

    /** {@code <home>/secrets/vectolyn.env}. */
    public Path secretsFile() {
        return secretsDir().resolve(SECRETS_FILE);
    }

    /** {@code <home>/backups}, the default folder of {@code backup.yml}. */
    public Path backupsDir() {
        return root.resolve(BACKUPS_DIR);
    }

    /** {@code <home>/logs}. */
    public Path logsDir() {
        return root.resolve(LOGS_DIR);
    }

    /** {@code <home>/state}. */
    public Path stateDir() {
        return root.resolve(STATE_DIR);
    }

    /** {@code groups/<name>.yml}, relative to {@code config/}, with forward slashes (as used in errors and events). */
    public static String groupFileName(String name) {
        return GROUPS_DIR + "/" + name + EXTENSION;
    }

    /** {@code tasks/<name>.yml}, relative to {@code config/}, with forward slashes. */
    public static String taskFileName(String name) {
        return TASKS_DIR + "/" + name + EXTENSION;
    }

    /**
     * Resolves a configuration file name such as {@code groups/lobby.yml} inside {@code config/}.
     *
     * @throws IllegalArgumentException if the name would leave {@code config/} or the group/task name is invalid
     */
    public Path resolve(String relative) {
        String[] parts = relative.split("/", -1);
        boolean valid = switch (parts.length) {
            case 1 -> parts[0].equals(CLOUD_FILE) || parts[0].equals(NODES_FILE);
            case 2 -> (parts[0].equals(GROUPS_DIR) || parts[0].equals(TASKS_DIR))
                    && parts[1].endsWith(EXTENSION)
                    && ConfigNames.isValidName(parts[1].substring(0, parts[1].length() - EXTENSION.length()));
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("not a configuration file of the cloud home");
        }
        Path config = configDir();
        Path folder = parts.length == 2 ? config.resolve(parts[0]) : config;
        Path resolved = config.resolve(relative).normalize();
        if (!folder.equals(resolved.getParent())) {
            throw new IllegalArgumentException("not a configuration file of the cloud home");
        }
        return resolved;
    }

    /** One plain folder name below {@code parent}; refuses anything that would leave it. */
    private static Path child(Path parent, String name) {
        if (name == null || !name.matches(ConfigNames.TEMPLATE) || name.contains("..")) {
            throw new IllegalArgumentException("not a plain folder name");
        }
        Path resolved = parent.resolve(name).normalize();
        if (!parent.equals(resolved.getParent())) {
            throw new IllegalArgumentException("not a plain folder name");
        }
        return resolved;
    }
}
