package net.vectora.cloud.download.provider;

import java.io.IOException;
import net.vectora.cloud.download.http.HostAllowlist;

/**
 * One download source. Resolving only talks to the source's API; the download itself is done
 * by {@link net.vectora.cloud.download.cache.ArtifactDownloader}, which checks the checksum.
 *
 * <p>Implementations: {@link PaperFillProvider}, {@link FabricMetaProvider}, {@link MojangProvider},
 * {@link BibliothekProvider} (GeyserMC, PandaSpigot), {@link ModrinthProvider}, {@link HangarProvider},
 * {@link MariaDbProvider}, {@link AdoptiumProvider} (JREs; reads {@link ArtifactRequest#system()} and
 * options like {@code image-type}).
 */
public interface ArtifactProvider {

    /**
     * Returns the id used in the catalog and the lockfile.
     *
     * @return the provider id, lower case
     */
    String id();

    /**
     * Returns every host this source may use: API, download and redirect targets.
     *
     * @return the allowlist
     */
    HostAllowlist allowedHosts();

    /**
     * Finds the download URL and the checksum for a request.
     *
     * @param request what to resolve
     * @return the artifact
     * @throws IOException if the API fails or the artifact does not exist
     *         ({@link net.vectora.cloud.download.ArtifactNotAvailableException})
     * @throws InterruptedException if interrupted
     */
    ResolvedArtifact resolve(ArtifactRequest request) throws IOException, InterruptedException;
}
