package net.vectora.cloud.download.http;

import java.net.URI;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The hosts one download source may use, including the hosts its redirects lead to.
 *
 * <p>Only exact host names, no wildcards. A URL passes only if it uses {@code https}, has no user
 * info, uses the default port 443 and its host is on the list.
 */
public final class HostAllowlist {

    private final Set<String> hosts;

    private HostAllowlist(Set<String> hosts) {
        this.hosts = hosts;
    }

    /**
     * Creates an allowlist.
     *
     * @param hosts exact host names
     * @return the allowlist
     * @throws IllegalArgumentException if the list is empty or a name is blank
     */
    public static HostAllowlist of(String... hosts) {
        return of(Set.of(hosts));
    }

    /**
     * Creates an allowlist.
     *
     * @param hosts exact host names
     * @return the allowlist
     * @throws IllegalArgumentException if the list is empty or a name is blank
     */
    public static HostAllowlist of(Collection<String> hosts) {
        if (hosts.isEmpty()) {
            throw new IllegalArgumentException("an allowlist needs at least one host");
        }
        Set<String> normalised = new TreeSet<>();
        for (String host : hosts) {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("blank host in allowlist");
            }
            normalised.add(host.toLowerCase(Locale.ROOT));
        }
        return new HostAllowlist(Set.copyOf(normalised));
    }

    /**
     * Checks a URL.
     *
     * @param uri the URL
     * @return {@code true} if the URL is HTTPS on port 443 to an allowed host without user info
     */
    public boolean permits(URI uri) {
        if (uri == null || !uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        if (uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return false;
        }
        String host = uri.getHost();
        return host != null && hosts.contains(host.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the allowed hosts.
     *
     * @return the host names, sorted
     */
    public Set<String> hosts() {
        return new TreeSet<>(hosts);
    }

    @Override
    public String toString() {
        return "HostAllowlist" + new TreeSet<>(hosts);
    }
}
