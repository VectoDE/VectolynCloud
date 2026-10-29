package net.vectora.cloud.config.operation;

import java.util.Objects;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.model.WantedServers;

/**
 * Switches one server on or off.
 *
 * @param server the server
 * @param wanted whether the cloud should create and start it
 */
public record SetServerWanted(ServerKind server, boolean wanted) implements ConfigOperation {

    /** Checks the argument. */
    public SetServerWanted {
        Objects.requireNonNull(server, "server");
    }

    @Override
    public ConfigSnapshot apply(ConfigSnapshot current) {
        WantedServers servers = current.cloud().servers() == null
                ? WantedServers.none()
                : current.cloud().servers();
        return current.withCloud(current.cloud().withServers(servers.with(server, wanted)));
    }
}
