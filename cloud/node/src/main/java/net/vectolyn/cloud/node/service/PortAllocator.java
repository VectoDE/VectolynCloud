package net.vectolyn.cloud.node.service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import net.vectolyn.cloud.config.model.PortRange;

/**
 * Hands out the ports of a node's {@code service-ports} range so that no two services get the same
 * port, no service gets a fixed port of the node (proxy, API, database, channel), and no service
 * gets a port that another program already listens on. Thread-safe.
 */
public final class PortAllocator {

    /** Checks whether the operating system would let a server bind a port. Replaceable in tests. */
    @FunctionalInterface
    public interface PortProbe {

        /**
         * Tells whether nothing listens on {@code port}.
         *
         * @param port the port
         * @return whether it can be bound
         */
        boolean isFree(int port);

        /**
         * Probe that binds a {@link ServerSocket} on {@code host} and closes it again.
         *
         * @param host address the services bind to
         * @return the probe
         */
        static PortProbe binding(String host) {
            return port -> {
                try (ServerSocket socket = new ServerSocket()) {
                    socket.setReuseAddress(false);
                    socket.bind(new InetSocketAddress(InetAddress.getByName(host), port));
                    return true;
                } catch (IOException e) {
                    return false;
                }
            };
        }
    }

    private final PortRange range;
    private final Set<Integer> reserved;
    private final PortProbe probe;
    private final Set<Integer> inUse = new HashSet<>();

    /**
     * Creates an allocator.
     *
     * @param range    the node's service port range
     * @param reserved fixed ports of the node that are never handed out
     * @param probe    check against the operating system
     */
    public PortAllocator(PortRange range, Collection<Integer> reserved, PortProbe probe) {
        this.range = Objects.requireNonNull(range, "range");
        this.reserved = Set.copyOf(reserved);
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    /**
     * Hands out a port: {@code preferred} if it lies in the range and is free (so a static service
     * keeps its port across restarts), otherwise the lowest free port of the range.
     *
     * @param preferred port to try first, 0 for none
     * @return the port, now marked as in use
     * @throws ServiceException if every port of the range is taken
     */
    public synchronized int allocate(int preferred) throws ServiceException {
        if (preferred != 0 && range.contains(preferred) && available(preferred)) {
            inUse.add(preferred);
            return preferred;
        }
        for (int port = range.first(); port <= range.last(); port++) {
            if (available(port)) {
                inUse.add(port);
                return port;
            }
        }
        throw new ServiceException("no free port left in the service port range " + range);
    }

    /**
     * Marks a port as used by something the allocator did not hand out (a fixed proxy port, an
     * orphaned process). Ports outside the range are accepted and simply tracked.
     *
     * @param port the port
     * @return false if the port was already in use
     */
    public synchronized boolean claim(int port) {
        return inUse.add(port);
    }

    /**
     * Gives a port back.
     *
     * @param port the port
     */
    public synchronized void release(int port) {
        inUse.remove(port);
    }

    /**
     * Tells whether the allocator currently counts a port as used.
     *
     * @param port the port
     * @return whether it is handed out or claimed
     */
    public synchronized boolean isInUse(int port) {
        return inUse.contains(port);
    }

    private boolean available(int port) {
        return !inUse.contains(port) && !reserved.contains(port) && probe.isFree(port);
    }
}
