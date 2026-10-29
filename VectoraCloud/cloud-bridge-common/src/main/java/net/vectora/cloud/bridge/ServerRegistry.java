package net.vectora.cloud.bridge;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.vectora.protocol.message.RegisterServer;
import net.vectora.protocol.message.UnregisterServer;

/**
 * Applies {@code RegisterServer} and {@code UnregisterServer} from the cloud to the proxy's server
 * list. Only the cloud can send these messages (a service that sends one is disconnected, see
 * docs/cloud/network.md); the registry still guards the proxy against a confused cloud:
 *
 * <ul>
 *   <li>A server whose {@code legacy} flag does not fit the {@link ProxyRole} is refused, so a
 *       1.8.8 backend never ends up behind the main proxy and the other way round.</li>
 *   <li>Servers from {@code velocity.toml} are never replaced or removed.</li>
 *   <li>A name that another plugin registered is left alone.</li>
 *   <li>Only servers this registry added are removed again.</li>
 * </ul>
 *
 * <p>Thread-safe. Called on the client's event thread, never on a proxy thread.
 */
public final class ServerRegistry {

    private final ProxyServers servers;
    private final ProxyRole role;
    private final Map<String, Address> owned = new HashMap<>();

    /** Creates a registry for the proxy {@code servers} in the given role. */
    public ServerRegistry(ProxyServers servers, ProxyRole role) {
        this.servers = Objects.requireNonNull(servers, "servers");
        this.role = Objects.requireNonNull(role, "role");
    }

    /** Adds or updates the server. */
    public synchronized Outcome register(RegisterServer message) {
        String name = message.name().value();
        if (!role.accepts(message.legacy())) {
            return Outcome.REFUSED_ROLE;
        }
        if (servers.isConfigured(name)) {
            return Outcome.REFUSED_CONFIGURED;
        }
        Address address = new Address(message.host(), message.port());
        Address previous = owned.get(name);
        if (previous == null && servers.exists(name)) {
            return Outcome.REFUSED_FOREIGN;
        }
        if (address.equals(previous)) {
            return Outcome.UNCHANGED;
        }
        if (previous != null) {
            servers.unregister(name);
            owned.remove(name);
        }
        servers.register(name, address.host(), address.port());
        owned.put(name, address);
        return previous == null ? Outcome.REGISTERED : Outcome.REPLACED;
    }

    /** Removes the server if this registry added it. */
    public synchronized Outcome unregister(UnregisterServer message) {
        String name = message.name().value();
        if (owned.remove(name) == null) {
            return Outcome.NOT_OWNED;
        }
        servers.unregister(name);
        return Outcome.UNREGISTERED;
    }

    /** Names of the servers this registry added. */
    public synchronized Set<String> registered() {
        return Set.copyOf(owned.keySet());
    }

    /** What {@link #register} or {@link #unregister} did. */
    public enum Outcome {
        /** The server was added. */
        REGISTERED,
        /** The server was known with another address and was replaced. */
        REPLACED,
        /** The server was known with the same address. */
        UNCHANGED,
        /** The {@code legacy} flag does not fit this proxy. */
        REFUSED_ROLE,
        /** The name belongs to a server in {@code velocity.toml}. */
        REFUSED_CONFIGURED,
        /** The name belongs to a server another plugin registered. */
        REFUSED_FOREIGN,
        /** The server was removed. */
        UNREGISTERED,
        /** The registry had not added this server; nothing was removed. */
        NOT_OWNED;

        /** Whether the message was refused. */
        public boolean refused() {
            return this == REFUSED_ROLE || this == REFUSED_CONFIGURED || this == REFUSED_FOREIGN;
        }
    }

    private record Address(String host, int port) {
    }
}
