// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.cluster;

import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.io.ConfigMappers;
import net.vectolyn.cloud.config.template.TemplatePaths;
import net.vectolyn.cloud.config.validation.ConfigNames;

/**
 * Which paths the mirror carries and the limits of what it carries. The head and the member use
 * the same rules: the head offers nothing else, the member accepts nothing else.
 *
 * <p>The path rules themselves live in {@link TemplatePaths} (module {@code cloud/config}), so the
 * template endpoints of the API apply exactly the same ones.
 */
public final class MirrorPaths {

    /** Longest path in UTF-8 bytes. */
    public static final int MAX_PATH_BYTES = TemplatePaths.MAX_PATH_BYTES;
    /** Longest segment of a path in UTF-8 bytes. */
    public static final int MAX_SEGMENT_BYTES = TemplatePaths.MAX_SEGMENT_BYTES;
    /** Most segments of a path. */
    public static final int MAX_DEPTH = TemplatePaths.MAX_DEPTH;

    /** Most mirrored config files ({@code nodes.yml}, groups and tasks together). */
    public static final int MAX_CONFIG_FILES = 1024;
    /** Largest mirrored config file, the limit of the config reader. */
    public static final long MAX_CONFIG_FILE_BYTES = ConfigMappers.MAX_FILE_BYTES;
    /** Largest mirrored config in total. */
    public static final long MAX_CONFIG_BYTES = 32L * 1024 * 1024;

    /** Most templates. */
    public static final int MAX_TEMPLATES = 256;
    /** Most files of one template. */
    public static final int MAX_TEMPLATE_FILES = 20_000;
    /** Largest file of a template. */
    public static final long MAX_TEMPLATE_FILE_BYTES = 1024L * 1024 * 1024;
    /** Largest template in total. */
    public static final long MAX_TEMPLATE_BYTES = 4L * 1024 * 1024 * 1024;
    /** Largest total of all templates. */
    public static final long MAX_TEMPLATES_BYTES = 16L * 1024 * 1024 * 1024;

    private MirrorPaths() {
    }

    /**
     * Whether a path is one the mirror may carry and a member may write; the rules of
     * {@link TemplatePaths#isSafePath}, shared with the template endpoints of the API.
     *
     * @param path the path, from the network or a file walk
     * @return whether it is safe
     */
    public static boolean isSafePath(String path) {
        return TemplatePaths.isSafePath(path);
    }

    /**
     * Whether a name is a template the mirror carries ({@link TemplatePaths#isTemplateName}).
     *
     * @param name the folder name
     * @return whether it is a template name
     */
    public static boolean isTemplateName(String name) {
        return TemplatePaths.isTemplateName(name);
    }

    /**
     * Whether a template file looks like a secret, which the head never offers and a member never
     * accepts ({@link TemplatePaths#looksSecret}).
     *
     * @param path relative path inside the template
     * @return whether it looks like a secret
     */
    public static boolean looksSecret(String path) {
        return TemplatePaths.looksSecret(path);
    }

    /**
     * Whether a folder of a template is left out as a whole ({@link TemplatePaths#isSecretFolder}).
     *
     * @param name the folder's name
     * @return whether it is left out
     */
    public static boolean isSecretFolder(String name) {
        return TemplatePaths.isSecretFolder(name);
    }

    /**
     * Whether a config file is mirrored: exactly {@code nodes.yml}, {@code groups/<name>.yml} and
     * {@code tasks/<name>.yml}. Never {@code cloud.yml}, {@code monitor.yml}, {@code backup.yml},
     * {@code software.lock.yml}, {@code cluster.yml} or anything from {@code secrets/}.
     *
     * @param path relative to {@code config/}
     */
    public static boolean isMirroredConfig(String path) {
        if (path == null) {
            return false;
        }
        if (path.equals(CloudHome.NODES_FILE)) {
            return true;
        }
        for (String folder : new String[] {CloudHome.GROUPS_DIR, CloudHome.TASKS_DIR}) {
            String prefix = folder + "/";
            if (path.startsWith(prefix) && path.endsWith(CloudHome.EXTENSION)) {
                String name = path.substring(prefix.length(), path.length() - CloudHome.EXTENSION.length());
                if (ConfigNames.isValidName(name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
