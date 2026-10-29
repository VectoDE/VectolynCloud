package net.vectora.cloud.network;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * Counts open connections in total and per IP address. Thread-safe; a slot is taken when a
 * connection is accepted and given back when it closes, authenticated or not.
 */
final class ConnectionLimiter {

    private final int maxTotal;
    private final int maxPerAddress;
    private final Map<InetAddress, Integer> perAddress = new HashMap<>();
    private int total;

    ConnectionLimiter(int maxTotal, int maxPerAddress) {
        this.maxTotal = maxTotal;
        this.maxPerAddress = maxPerAddress;
    }

    /** Takes a slot for {@code address}; returns {@code false} if a limit is reached. */
    synchronized boolean tryAcquire(InetAddress address) {
        int fromAddress = perAddress.getOrDefault(address, 0);
        if (total >= maxTotal || fromAddress >= maxPerAddress) {
            return false;
        }
        total++;
        perAddress.put(address, fromAddress + 1);
        return true;
    }

    /** Gives back a slot taken by {@link #tryAcquire}. */
    synchronized void release(InetAddress address) {
        Integer fromAddress = perAddress.get(address);
        if (fromAddress == null) {
            return;
        }
        total--;
        if (fromAddress <= 1) {
            perAddress.remove(address);
        } else {
            perAddress.put(address, fromAddress - 1);
        }
    }

    /** Returns the number of open connections. */
    synchronized int open() {
        return total;
    }
}
