package net.vectora.cloud.network;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * Counts open connections in total and per {@link AddressKey} (IPv4 address, or /64 of IPv6),
 * and separately the connections that have not authenticated yet. Thread-safe.
 *
 * <p>A {@link Slot} is taken when a connection is accepted. It counts as unauthenticated until
 * {@link Slot#authenticated()} and is given back by {@link Slot#release()} when the connection
 * closes, authenticated or not. The unauthenticated limits are smaller than the others, so a peer
 * that opens connections and never says {@code Hello} cannot keep services from connecting.
 *
 * <p>Public for the cluster port ({@code cloud/cluster}), which counts its peers the same way.
 */
public final class ConnectionLimiter {

    private final int maxTotal;
    private final int maxPerAddress;
    private final int maxUnauthenticated;
    private final int maxUnauthenticatedPerAddress;
    private final Map<AddressKey, Integer> perAddress = new HashMap<>();
    private final Map<AddressKey, Integer> unauthenticatedPerAddress = new HashMap<>();
    private int total;
    private int unauthenticated;

    /**
     * Creates a limiter.
     *
     * @param maxTotal                     open connections in total
     * @param maxPerAddress                open connections per {@link AddressKey}
     * @param maxUnauthenticated           connections not authenticated yet, in total
     * @param maxUnauthenticatedPerAddress connections not authenticated yet, per {@link AddressKey}
     */
    public ConnectionLimiter(int maxTotal, int maxPerAddress, int maxUnauthenticated, int maxUnauthenticatedPerAddress) {
        this.maxTotal = maxTotal;
        this.maxPerAddress = maxPerAddress;
        this.maxUnauthenticated = maxUnauthenticated;
        this.maxUnauthenticatedPerAddress = maxUnauthenticatedPerAddress;
    }

    /**
     * Takes an unauthenticated slot for a connection from {@code address}.
     *
     * @return the slot, or {@code null} if a limit is reached
     */
    public synchronized Slot tryAcquire(InetAddress address) {
        AddressKey key = AddressKey.of(address);
        if (total >= maxTotal || perAddress.getOrDefault(key, 0) >= maxPerAddress
                || unauthenticated >= maxUnauthenticated
                || unauthenticatedPerAddress.getOrDefault(key, 0) >= maxUnauthenticatedPerAddress) {
            return null;
        }
        total++;
        unauthenticated++;
        perAddress.merge(key, 1, Integer::sum);
        unauthenticatedPerAddress.merge(key, 1, Integer::sum);
        return new Slot(key);
    }

    /** Returns the number of open connections. */
    public synchronized int open() {
        return total;
    }

    /** Returns the number of open connections that have not authenticated yet. */
    public synchronized int unauthenticated() {
        return unauthenticated;
    }

    private static void decrement(Map<AddressKey, Integer> counts, AddressKey key) {
        counts.computeIfPresent(key, (k, count) -> count <= 1 ? null : count - 1);
    }

    /** The share of one connection. */
    public final class Slot {

        private final AddressKey key;
        private boolean pending = true;
        private boolean released;

        private Slot(AddressKey key) {
            this.key = key;
        }

        /** Returns the key the connection is counted under. */
        public AddressKey key() {
            return key;
        }

        /** The connection has authenticated: it no longer counts against the unauthenticated limits. */
        public void authenticated() {
            synchronized (ConnectionLimiter.this) {
                if (pending && !released) {
                    pending = false;
                    unauthenticated--;
                    decrement(unauthenticatedPerAddress, key);
                }
            }
        }

        /** The connection has closed. Safe to call more than once. */
        public void release() {
            synchronized (ConnectionLimiter.this) {
                if (released) {
                    return;
                }
                released = true;
                total--;
                decrement(perAddress, key);
                if (pending) {
                    pending = false;
                    unauthenticated--;
                    decrement(unauthenticatedPerAddress, key);
                }
            }
        }
    }
}
