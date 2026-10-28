package net.vectora.cloud.download.cache;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.vectora.cloud.download.ChecksumMismatchException;
import net.vectora.cloud.download.TofuMismatchException;
import net.vectora.cloud.download.hash.Checksum;
import net.vectora.cloud.download.hash.HashAlgorithm;
import net.vectora.cloud.download.hash.MultiDigest;
import net.vectora.cloud.download.http.DownloadClient;
import net.vectora.cloud.download.http.HostAllowlist;
import net.vectora.cloud.download.lock.LockedArtifact;
import net.vectora.cloud.download.lock.Trust;
import net.vectora.cloud.download.provider.ProviderRegistry;
import net.vectora.cloud.download.provider.ResolvedArtifact;

/**
 * Downloads an artifact into the {@link ArtifactCache} and verifies it.
 *
 * <ol>
 *   <li>If the SHA-256 is known (from the source or the lockfile) and the cache has a matching
 *       file, nothing is downloaded.</li>
 *   <li>Otherwise the file is streamed into a {@code .part} file while SHA-256 and the source's
 *       checksum are computed in one pass.</li>
 *   <li>On a mismatch the {@code .part} file is deleted and the download fails.</li>
 *   <li>Only a verified file is moved, atomically, to {@code cache/artifacts/<sha256>}.</li>
 * </ol>
 *
 * <p>Sources without a checksum (Fabric server launcher) are trusted on first use: the first
 * download is accepted and its SHA-256 goes into the lockfile marked {@code tofu}. Every later
 * download for the same coordinate must match it, or {@link TofuMismatchException} is thrown.
 */
public final class ArtifactDownloader {

    /** Size limit for a download whose size the source does not announce. */
    public static final long DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024;

    private final DownloadClient client;
    private final ProviderRegistry providers;
    private final ArtifactCache cache;
    private final long defaultMaxBytes;

    /**
     * Creates the downloader with a 2 GiB limit for downloads of unknown size.
     *
     * @param client the HTTP client
     * @param providers the providers, for their host allowlists
     * @param cache the cache
     */
    public ArtifactDownloader(DownloadClient client, ProviderRegistry providers, ArtifactCache cache) {
        this(client, providers, cache, DEFAULT_MAX_BYTES);
    }

    /**
     * Creates the downloader.
     *
     * @param client the HTTP client
     * @param providers the providers, for their host allowlists
     * @param cache the cache
     * @param defaultMaxBytes size limit for a download whose size the source does not announce
     */
    public ArtifactDownloader(DownloadClient client, ProviderRegistry providers, ArtifactCache cache,
            long defaultMaxBytes) {
        this.client = Objects.requireNonNull(client, "client");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.cache = Objects.requireNonNull(cache, "cache");
        if (defaultMaxBytes <= 0) {
            throw new IllegalArgumentException("defaultMaxBytes must be positive");
        }
        this.defaultMaxBytes = defaultMaxBytes;
    }

    /**
     * Downloads a freshly resolved artifact.
     *
     * @param artifact the resolved artifact
     * @param locked the current lock entry for the same name, or {@code null}; for a TOFU artifact
     *        with the same coordinate its pinned SHA-256 is enforced
     * @return the verified file
     * @throws ChecksumMismatchException if the file does not match the source's checksum, or the
     *         source now publishes a different checksum than the one in the lockfile
     * @throws TofuMismatchException if a TOFU artifact no longer matches its pinned hash
     * @throws IOException on other download errors
     * @throws InterruptedException if interrupted
     */
    public DownloadedArtifact download(ResolvedArtifact artifact, LockedArtifact locked)
            throws IOException, InterruptedException {
        LockedArtifact sameCoordinate = locked != null && locked.coordinate().equals(artifact.coordinate())
                ? locked : null;
        String label = artifact.coordinate().toString();
        Checksum expected;
        Checksum pinnedSha256 = null;
        Trust trust;
        if (artifact.checksum().isPresent()) {
            expected = artifact.checksum().get();
            trust = Trust.SOURCE;
            if (sameCoordinate != null && sameCoordinate.trust() == Trust.SOURCE
                    && sameCoordinate.checksum().algorithm() == expected.algorithm()
                    && !sameCoordinate.checksum().matches(expected)) {
                throw new ChecksumMismatchException(label + ": the source now publishes " + expected
                        + ", the lockfile has " + sameCoordinate.checksum() + " for the same version and build");
            }
            if (sameCoordinate != null && sameCoordinate.checksum().matches(expected)) {
                pinnedSha256 = sameCoordinate.sha256();
            }
        } else {
            trust = Trust.TOFU;
            expected = sameCoordinate == null ? null : sameCoordinate.sha256();
        }
        long maxBytes = artifact.size().isPresent() ? artifact.size().getAsLong() : defaultMaxBytes;
        return fetch(label, artifact.url(), providers.allowedHosts(artifact.coordinate().provider()), expected,
                pinnedSha256, trust, maxBytes);
    }

    /**
     * Downloads exactly what a lock entry describes, without asking the source's API again.
     *
     * @param locked the lock entry
     * @return the verified file
     * @throws ChecksumMismatchException or {@link TofuMismatchException} if the file differs
     * @throws IOException on other download errors
     * @throws InterruptedException if interrupted
     */
    public DownloadedArtifact download(LockedArtifact locked) throws IOException, InterruptedException {
        return fetch(locked.coordinate().toString(), locked.url(), providers.allowedHosts(locked.coordinate().provider()),
                locked.checksum(), locked.sha256(), locked.trust(), locked.size());
    }

    private DownloadedArtifact fetch(String label, URI url, HostAllowlist hosts, Checksum expected,
            Checksum expectedSha256, Trust trust, long maxBytes) throws IOException, InterruptedException {
        Checksum knownSha256 = expectedSha256;
        if (knownSha256 == null && expected != null && expected.algorithm() == HashAlgorithm.SHA256) {
            knownSha256 = expected;
        }
        if (knownSha256 != null) {
            var cached = cache.lookup(knownSha256, expected);
            if (cached.isPresent()) {
                Checksum verified = expected == null ? knownSha256 : expected;
                return new DownloadedArtifact(cached.get(), verified, knownSha256, trust, Files.size(cached.get()), true);
            }
        }

        List<HashAlgorithm> algorithms = new ArrayList<>();
        if (expected != null) {
            algorithms.add(expected.algorithm());
        }
        Path part = cache.newPartFile();
        boolean committed = false;
        try {
            Map<HashAlgorithm, Checksum> actual = client.get(url, hosts, maxBytes, (body, length) -> {
                MultiDigest digest = new MultiDigest(algorithms);
                try (OutputStream out = Files.newOutputStream(part, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    copy(body, out, digest);
                }
                return digest.finish();
            });
            Checksum sha256 = actual.get(HashAlgorithm.SHA256);
            if (expected != null && !actual.get(expected.algorithm()).matches(expected)) {
                throw mismatch(label, trust, expected, actual.get(expected.algorithm()));
            }
            if (knownSha256 != null && !sha256.matches(knownSha256)) {
                throw mismatch(label, trust, knownSha256, sha256);
            }
            long size = Files.size(part);
            Path file = cache.commit(part, sha256);
            committed = true;
            return new DownloadedArtifact(file, expected == null ? sha256 : expected, sha256, trust, size, false);
        } finally {
            if (!committed) {
                Files.deleteIfExists(part);
            }
        }
    }

    private static IOException mismatch(String label, Trust trust, Checksum expected, Checksum actual) {
        if (trust == Trust.TOFU) {
            return new TofuMismatchException(label + ": the lockfile pinned " + expected + " on the first download, "
                    + "this download has " + actual + ". The file was deleted. If the change is expected, remove the "
                    + "entry from software.lock.yml to pin the new file.");
        }
        return new ChecksumMismatchException(label + ": expected " + expected + ", got " + actual
                + ". The file was deleted.");
    }

    private static void copy(InputStream in, OutputStream out, MultiDigest digest) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (read > 0) {
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
    }
}
