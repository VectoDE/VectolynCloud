package net.vectora.cloud.config;

import net.vectora.cloud.config.error.ConfigException;

/**
 * Receives configuration changes from {@link ConfigService}.
 *
 * <p>Listeners run one at a time, in registration order, while the service holds its lock. That
 * keeps events in the same order as the changes. A listener may read the snapshot and may call
 * {@link ConfigService#apply} itself, but it must return quickly and must not wait for another
 * thread that uses the service. An exception from a listener is logged and does not stop the
 * other listeners.
 */
@FunctionalInterface
public interface ConfigListener {

    /** The configuration changed and is already on disk. */
    void configChanged(ConfigChanged change);

    /**
     * A file edited by hand could not be loaded or is invalid. The previous configuration stays
     * active until the file is fixed.
     */
    default void reloadFailed(ConfigException error) {
    }
}
