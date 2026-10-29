package net.vectora.cloud.config;

import java.nio.file.Path;
import java.util.Objects;
import net.vectora.cloud.config.validation.ConfigNames;

/**
 * File layout of the cloud home, the folder the cloud runs in (never a folder of the repository).
 *
 * <pre>
 * &lt;home&gt;/cloud.yml
 * &lt;home&gt;/nodes.yml
 * &lt;home&gt;/groups/&lt;group&gt;.yml
 * &lt;home&gt;/tasks/&lt;task&gt;.yml
 * &lt;home&gt;/secrets/vectora.env
 * </pre>
 *
 * Other folders of the home ({@code temp/}, {@code cache/}, {@code backups/}, {@code logs/},
 * {@code state/}, {@code software.lock.yml}) belong to other modules.
 *
 * @param root the home directory
 */
public record CloudHome(Path root) {

    /** {@code cloud.yml}, relative to the home. */
    public static final String CLOUD_FILE = "cloud.yml";
    /** {@code nodes.yml}, relative to the home. */
    public static final String NODES_FILE = "nodes.yml";
    /** Folder of the group files. */
    public static final String GROUPS_DIR = "groups";
    /** Folder of the task files. */
    public static final String TASKS_DIR = "tasks";
    /** Folder of the secrets. */
    public static final String SECRETS_DIR = "secrets";
    /** Secrets file inside {@link #SECRETS_DIR}. */
    public static final String SECRETS_FILE = "vectora.env";
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

    /** {@code <home>/cloud.yml}. */
    public Path cloudFile() {
        return root.resolve(CLOUD_FILE);
    }

    /** {@code <home>/nodes.yml}. */
    public Path nodesFile() {
        return root.resolve(NODES_FILE);
    }

    /** {@code <home>/groups}. */
    public Path groupsDir() {
        return root.resolve(GROUPS_DIR);
    }

    /** {@code <home>/tasks}. */
    public Path tasksDir() {
        return root.resolve(TASKS_DIR);
    }

    /** {@code <home>/secrets}. */
    public Path secretsDir() {
        return root.resolve(SECRETS_DIR);
    }

    /** {@code <home>/secrets/vectora.env}. */
    public Path secretsFile() {
        return secretsDir().resolve(SECRETS_FILE);
    }

    /** {@code groups/<name>.yml}, relative, with forward slashes (as used in errors and events). */
    public static String groupFileName(String name) {
        return GROUPS_DIR + "/" + name + EXTENSION;
    }

    /** {@code tasks/<name>.yml}, relative, with forward slashes. */
    public static String taskFileName(String name) {
        return TASKS_DIR + "/" + name + EXTENSION;
    }

    /**
     * Resolves a relative file name such as {@code groups/lobby.yml} inside the home.
     *
     * @throws IllegalArgumentException if the name would leave the home or the group/task name is invalid
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
        Path folder = parts.length == 2 ? root.resolve(parts[0]) : root;
        Path resolved = root.resolve(relative).normalize();
        if (!folder.equals(resolved.getParent())) {
            throw new IllegalArgumentException("not a configuration file of the cloud home");
        }
        return resolved;
    }
}
