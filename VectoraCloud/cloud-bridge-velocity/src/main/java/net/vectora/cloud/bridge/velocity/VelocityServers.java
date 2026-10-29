package net.vectora.cloud.bridge.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.vectora.cloud.bridge.ProxyServers;

/** {@link ProxyServers} on Velocity's server list. */
final class VelocityServers implements ProxyServers {

    private final ProxyServer proxy;
    private final Map<String, ServerInfo> registered = new ConcurrentHashMap<>();

    VelocityServers(ProxyServer proxy) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
    }

    @Override
    public boolean isConfigured(String name) {
        return proxy.getConfiguration().getServers().containsKey(name);
    }

    @Override
    public boolean exists(String name) {
        return proxy.getServer(name).isPresent();
    }

    @Override
    public void register(String name, String host, int port) {
        // An IP literal (what the cloud sends) is parsed without a lookup; a host name is resolved
        // here, on the client's event thread, not on a proxy thread.
        ServerInfo info = new ServerInfo(name, new InetSocketAddress(host, port));
        proxy.registerServer(info);
        registered.put(name, info);
    }

    @Override
    public void unregister(String name) {
        ServerInfo info = registered.remove(name);
        if (info != null) {
            proxy.unregisterServer(info);
        }
    }
}
