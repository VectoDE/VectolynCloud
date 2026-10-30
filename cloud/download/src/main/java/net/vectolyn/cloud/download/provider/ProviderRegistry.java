package net.vectolyn.cloud.download.provider;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.vectolyn.cloud.download.http.DownloadClient;
import net.vectolyn.cloud.download.http.HostAllowlist;

/** The providers by id. The catalog and the lockfile refer to providers by these ids. */
public final class ProviderRegistry {

    private final Map<String, ArtifactProvider> providers;

    private ProviderRegistry(Map<String, ArtifactProvider> providers) {
        this.providers = Map.copyOf(providers);
    }

    /**
     * Creates a registry from providers.
     *
     * @param providers the providers; ids must be unique
     * @return the registry
     * @throws IllegalArgumentException on a duplicate id
     */
    public static ProviderRegistry of(Collection<? extends ArtifactProvider> providers) {
        Map<String, ArtifactProvider> byId = new TreeMap<>();
        for (ArtifactProvider provider : providers) {
            if (byId.putIfAbsent(provider.id(), provider) != null) {
                throw new IllegalArgumentException("duplicate provider id " + provider.id());
            }
        }
        return new ProviderRegistry(byId);
    }

    /**
     * Creates the registry with every provider of this module.
     *
     * @param client the HTTP client they share
     * @return the registry
     */
    public static ProviderRegistry standard(DownloadClient client) {
        return of(List.of(
                new PaperFillProvider(client),
                new FabricMetaProvider(client),
                new MojangProvider(client),
                BibliothekProvider.geyser(client),
                BibliothekProvider.pandaSpigot(client),
                new ModrinthProvider(client),
                new HangarProvider(client),
                new MariaDbProvider(client),
                new AdoptiumProvider(client)));
    }

    /**
     * Returns a registry with one more provider.
     *
     * @param provider the provider
     * @return the new registry
     * @throws IllegalArgumentException if the id is taken
     */
    public ProviderRegistry with(ArtifactProvider provider) {
        List<ArtifactProvider> all = new java.util.ArrayList<>(providers.values());
        all.add(provider);
        return of(all);
    }

    /**
     * Looks up a provider.
     *
     * @param id the provider id
     * @return the provider, or empty
     */
    public Optional<ArtifactProvider> find(String id) {
        return Optional.ofNullable(providers.get(id));
    }

    /**
     * Looks up a provider that must exist.
     *
     * @param id the provider id
     * @return the provider
     * @throws IllegalArgumentException if there is none
     */
    public ArtifactProvider get(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("unknown download provider " + id
                + ", known: " + providers.keySet()));
    }

    /**
     * Returns the hosts of a provider.
     *
     * @param id the provider id
     * @return its allowlist
     * @throws IllegalArgumentException if there is no such provider
     */
    public HostAllowlist allowedHosts(String id) {
        return get(id).allowedHosts();
    }

    /**
     * Returns the provider ids.
     *
     * @return the ids, sorted
     */
    public Collection<String> ids() {
        return new TreeMap<>(providers).keySet();
    }
}
