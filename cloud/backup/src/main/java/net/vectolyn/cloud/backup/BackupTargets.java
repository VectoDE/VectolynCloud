package net.vectolyn.cloud.backup;

import java.util.List;

/**
 * The static services of this node, implemented by cloud-node from its service plan: every static
 * task's services with their folders ({@code <home>/<template>/runtime} for index 1,
 * {@code runtime-<n>} otherwise), whether they run or not.
 */
@FunctionalInterface
public interface BackupTargets {

    /** The static services, in a stable order. */
    List<BackupTarget> staticServices();
}
