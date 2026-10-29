package net.vectora.cloud.node.channel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.vectora.protocol.ServiceId;

/**
 * Which backends each proxy was sent with {@code RegisterServer} and not yet withdrawn. The proxy
 * keeps its server list across a reconnect of its bridge; an {@code UnregisterServer} that fell
 * into the gap never reached it. When it connects again, {@link #stale} names what it still lists
 * although the backend is gone, so the node can withdraw exactly that.
 */
final class ProxyLists {

    private final Map<ServiceId, Set<ServiceId>> sent = new ConcurrentHashMap<>();

    /** A {@code RegisterServer} for {@code backend} went out to {@code proxy}. */
    void sent(ServiceId proxy, ServiceId backend) {
        sent.computeIfAbsent(proxy, key -> ConcurrentHashMap.newKeySet()).add(backend);
    }

    /** An {@code UnregisterServer} for {@code backend} went out to {@code proxy}. */
    void withdrawn(ServiceId proxy, ServiceId backend) {
        Set<ServiceId> backends = sent.get(proxy);
        if (backends != null) {
            backends.remove(backend);
        }
    }

    /**
     * The backends {@code proxy} may still list that are no longer among {@code current}, sorted by
     * name. They stay recorded until {@link #withdrawn} is called for them.
     *
     * @param proxy   the proxy
     * @param current the backends it should list now
     * @return the backends to withdraw
     */
    List<ServiceId> stale(ServiceId proxy, Set<ServiceId> current) {
        Set<ServiceId> backends = sent.get(proxy);
        if (backends == null) {
            return List.of();
        }
        List<ServiceId> stale = new ArrayList<>();
        for (ServiceId backend : backends) {
            if (!current.contains(backend)) {
                stale.add(backend);
            }
        }
        stale.sort(Comparator.comparing(ServiceId::value));
        return stale;
    }

    /** The proxy process ended: its next start begins with an empty list. */
    void proxyEnded(ServiceId proxy) {
        sent.remove(proxy);
    }
}
