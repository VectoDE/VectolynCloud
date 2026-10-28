package net.vectora.cloud.config.model;

/**
 * Inclusive port range.
 *
 * @param first first port
 * @param last  last port, at least {@code first}
 */
public record PortRange(int first, int last) {

    /** Whether {@code port} lies in the range. */
    public boolean contains(int port) {
        return port >= first && port <= last;
    }

    @Override
    public String toString() {
        return first + "-" + last;
    }
}
