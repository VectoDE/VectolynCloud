package net.vectora.cloud.template;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The proxy's server list, built from the wanted servers only.
 *
 * <p>It produces the values of three placeholders in {@code templates/Proxy/velocity.toml}:
 * <ul>
 *   <li>{@value #SERVERS}: one line per wanted server, {@code name = "host:port"}, sorted by name.
 *       Placed under {@code [servers]}.</li>
 *   <li>{@value #TRY}: the wanted servers of the try order, quoted and separated by
 *       {@code ", "}, in the given order. Placed inside {@code try = [ ... ]}.</li>
 *   <li>{@value #FORCED_HOSTS}: one line per forced host, {@code "host" = [ "server", ... ]},
 *       sorted by host. Unwanted servers are dropped from each list, and a host left without a
 *       server is dropped. Placed under {@code [forced-hosts]}.</li>
 * </ul>
 * Lines are separated by {@code \n}; the renderer turns that into the line ending of the file.
 * The output depends only on the content of the arguments, not on their iteration order (except
 * the try order and each host's server order, which are meant to be ordered).
 */
public final class ProxyServerList {

    /** Placeholder for the {@code [servers]} lines. */
    public static final String SERVERS = "VECTORA_PROXY_SERVERS";
    /** Placeholder for the content of {@code try = [ ... ]}. */
    public static final String TRY = "VECTORA_PROXY_TRY";
    /** Placeholder for the {@code [forced-hosts]} lines. */
    public static final String FORCED_HOSTS = "VECTORA_PROXY_FORCED_HOSTS";

    private static final Pattern FORCED_HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}");

    private final String servers;
    private final String tryOrder;
    private final String forcedHosts;

    private ProxyServerList(String servers, String tryOrder, String forcedHosts) {
        this.servers = servers;
        this.tryOrder = tryOrder;
        this.forcedHosts = forcedHosts;
    }

    /**
     * Builds the server list.
     *
     * @param backends every backend the cloud knows, wanted or not; names are unique ignoring case
     * @param tryOrder server names tried on login and after a kick, in this order; unwanted ones
     *     are dropped, repeated ones count once
     * @param forcedHosts host name to the servers tried for players who join through it, in order
     * @return the server list
     * @throws IllegalArgumentException if names repeat, a try entry or forced host names an unknown
     *     server, or a forced host is not a plain host name
     */
    public static ProxyServerList of(Collection<ProxyBackend> backends, List<String> tryOrder,
            Map<String, List<String>> forcedHosts) {
        Map<String, ProxyBackend> byName = new TreeMap<>();
        Set<String> seen = new HashSet<>();
        for (ProxyBackend backend : backends) {
            if (!seen.add(backend.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("server name used twice: " + backend.name());
            }
            byName.put(backend.name(), backend);
        }

        String servers = byName.values().stream()
                .filter(ProxyBackend::wanted)
                .map(b -> b.name() + " = \"" + b.address() + "\"")
                .collect(Collectors.joining("\n"));

        String tries = quotedList(wantedOnly(byName, tryOrder, "try order"));

        Map<String, String> hostLines = new TreeMap<>();
        Map<String, String> hostsIgnoringCase = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : forcedHosts.entrySet()) {
            String host = Objects.requireNonNull(entry.getKey(), "forced host");
            if (!FORCED_HOST.matcher(host).matches()) {
                throw new IllegalArgumentException("forced host must be a plain host name");
            }
            String previous = hostsIgnoringCase.put(host.toLowerCase(Locale.ROOT), host);
            if (previous != null) {
                throw new IllegalArgumentException("forced host used twice: " + host);
            }
            List<String> targets = wantedOnly(byName, entry.getValue(), "forced host " + host);
            if (!targets.isEmpty()) {
                hostLines.put(host, "\"" + host + "\" = [ " + quotedList(targets) + " ]");
            }
        }
        return new ProxyServerList(servers, tries, String.join("\n", hostLines.values()));
    }

    private static List<String> wantedOnly(Map<String, ProxyBackend> byName, List<String> names, String where) {
        Set<String> result = new LinkedHashSet<>();
        for (String name : names) {
            ProxyBackend backend = byName.get(name);
            if (backend == null) {
                throw new IllegalArgumentException(where + " names an unknown server: " + name);
            }
            if (backend.wanted()) {
                result.add(name);
            }
        }
        return new ArrayList<>(result);
    }

    private static String quotedList(List<String> names) {
        return names.stream().map(name -> "\"" + name + "\"").collect(Collectors.joining(", "));
    }

    /**
     * Returns the value of {@value #SERVERS}.
     *
     * @return one {@code name = "host:port"} line per wanted server; empty if none is wanted
     */
    public String servers() {
        return servers;
    }

    /**
     * Returns the value of {@value #TRY}.
     *
     * @return the quoted, comma-separated try order; empty if no tried server is wanted
     */
    public String tryOrder() {
        return tryOrder;
    }

    /**
     * Returns the value of {@value #FORCED_HOSTS}.
     *
     * @return one {@code "host" = [ ... ]} line per forced host; empty if there is none
     */
    public String forcedHosts() {
        return forcedHosts;
    }

    /**
     * Returns the three values as placeholder values, to be combined with the others through
     * {@link PlaceholderValues#orElse(PlaceholderValues)}.
     *
     * @return values for {@value #SERVERS}, {@value #TRY} and {@value #FORCED_HOSTS}
     */
    public PlaceholderValues placeholderValues() {
        Map<String, String> values = Map.of(SERVERS, servers, TRY, tryOrder, FORCED_HOSTS, forcedHosts);
        return name -> Optional.ofNullable(values.get(name));
    }
}
