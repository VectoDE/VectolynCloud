package net.vectora.cloud.config.operation;

import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.model.ConfigSnapshot;

/**
 * One typed change to the configuration. Console commands, the setup assistant and later the REST
 * API and the dashboard all express their changes as operations and hand them to
 * {@code ConfigService.apply}, which validates the result, writes it atomically and notifies the
 * listeners.
 *
 * <p>An operation only computes the new snapshot; it does not validate the whole configuration
 * (the service does that) and has no side effects. It throws {@link ConfigException} when it cannot
 * apply at all, for example because the group it changes does not exist. New operations can be
 * added anywhere by implementing this interface.
 */
@FunctionalInterface
public interface ConfigOperation {

    /** The snapshot after this change. */
    ConfigSnapshot apply(ConfigSnapshot current) throws ConfigException;
}
