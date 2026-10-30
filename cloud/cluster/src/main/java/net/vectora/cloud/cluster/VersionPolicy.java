package net.vectora.cloud.cluster;

/**
 * Which VectoraCloud versions may form one cluster. The cluster runs one version: a node with
 * another version than the head does not join and does not get a session until it is updated to
 * the head's version. The update system ({@code #74}) plugs in here and through
 * {@link ClusterService.Events#versionMismatch(String, String)}, where a member learns the version
 * it has to update to.
 */
@FunctionalInterface
public interface VersionPolicy {

    /**
     * Whether a node of {@code member} may join or connect to a head of {@code head}.
     *
     * @param head   the head's version
     * @param member the other node's version
     * @return true if they fit
     */
    boolean compatible(String head, String member);

    /** The rule of the cluster: exactly the same version. */
    static VersionPolicy sameVersion() {
        return String::equals;
    }
}
