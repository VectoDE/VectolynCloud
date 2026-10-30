package net.vectolyn.cloud.download.provider;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import net.vectolyn.cloud.download.ArtifactNotAvailableException;
import net.vectolyn.cloud.download.MalformedResponseException;
import net.vectolyn.cloud.download.hash.Checksum;
import net.vectolyn.cloud.download.hash.HashAlgorithm;
import net.vectolyn.cloud.download.http.DownloadClient;
import net.vectolyn.cloud.download.http.HostAllowlist;
import tools.jackson.databind.JsonNode;

/**
 * MariaDB's REST API ({@code https://downloads.mariadb.org/rest-api/mariadb/}): resolves the server
 * archive for the managed database mode. Downloading and unpacking is done by the caller.
 *
 * <ul>
 *   <li>Platforms: Windows x64 (zip) and Linux x86_64 with glibc (tar.gz). Every other platform
 *       gets an {@link ArtifactNotAvailableException} that says so.</li>
 *   <li>{@code version}: a release ({@code 11.8.9}), a release series ({@code 11.8}, newest
 *       release of it) or empty (newest stable long-term-support series).</li>
 *   <li>The API lists {@code http://} URLs. They are rewritten to HTTPS and pinned to the
 *       {@code archive} mirror, because the default redirect goes to a geo-selected third-party
 *       mirror that no allowlist can know in advance. The checksum comes from the API either way.</li>
 *   <li>The API offers sha1, sha256 and sha512; the strongest one is used.</li>
 * </ul>
 */
public final class MariaDbProvider implements ArtifactProvider {

    /** Provider id. */
    public static final String ID = "mariadb";

    static final String API = "https://downloads.mariadb.org/rest-api/mariadb/";
    static final String MIRROR_QUERY = "mirror=archive";

    private static final HostAllowlist HOSTS = HostAllowlist.of("downloads.mariadb.org", "archive.mariadb.org");
    private static final Pattern SERIES = Pattern.compile("\\d{1,3}\\.\\d{1,3}");
    private static final Pattern RELEASE = Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    private final DownloadClient client;

    /**
     * Creates the provider.
     *
     * @param client the HTTP client
     */
    public MariaDbProvider(DownloadClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public HostAllowlist allowedHosts() {
        return HOSTS;
    }

    @Override
    public ResolvedArtifact resolve(ArtifactRequest request) throws IOException, InterruptedException {
        TargetPlatform system = request.system();
        Package wanted = packageFor(system);

        String version = request.version();
        Release release;
        if (version != null && RELEASE.matcher(version).matches()) {
            release = single(parseReleases(ApiSupport.fetchJson(client, URI.create(API + version + "/"), HOSTS,
                    "MariaDB " + version)), version);
        } else {
            String series = version;
            if (series == null) {
                series = newestLtsSeries(parseSeries(ApiSupport.fetchJson(client, URI.create(API), HOSTS,
                        "MariaDB release series")));
            } else if (!SERIES.matcher(series).matches()) {
                throw new IllegalArgumentException("MariaDB version must look like 11.8 or 11.8.9: " + series);
            }
            release = single(parseReleases(ApiSupport.fetchJson(client, URI.create(API + series + "/latest/"), HOSTS,
                    "MariaDB " + series)), series);
        }

        ReleaseFile file = release.files().stream().filter(wanted::matches).findFirst()
                .orElseThrow(() -> new ArtifactNotAvailableException("MariaDB " + release.id() + " has no "
                        + wanted.description() + " archive"));
        Checksum checksum = Checksum.strongest(file.checksums().values()).orElseThrow(() ->
                new ArtifactNotAvailableException("MariaDB " + file.name() + " has no checksum"));
        URI url = ApiSupport.checkDownloadUrl(archiveMirror(file.url()), HOSTS, "MariaDB " + file.name());
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("package", wanted.name().toLowerCase(Locale.ROOT));
        return new ResolvedArtifact(new ArtifactCoordinate(ID, request.project(), release.id(), null), url,
                ApiSupport.fileName(file.name(), ID), Optional.of(checksum), OptionalLong.empty(), OptionalInt.empty(), metadata);
    }

    /** The archive types the cloud can use. */
    enum Package {
        /** Windows x64 zip. */
        WINDOWS_ZIP("Windows x64 zip"),
        /** Linux x86_64 glibc tar.gz (the systemd build). */
        LINUX_TAR_GZ("Linux x86_64 glibc tar.gz");

        private final String description;

        Package(String description) {
            this.description = description;
        }

        String description() {
            return description;
        }

        boolean matches(ReleaseFile file) {
            String name = file.name().toLowerCase(Locale.ROOT);
            if (name.contains("debug") || !"x86_64".equalsIgnoreCase(file.cpu())) {
                return false;
            }
            return switch (this) {
                case WINDOWS_ZIP -> "windows".equalsIgnoreCase(file.os()) && name.endsWith(".zip");
                case LINUX_TAR_GZ -> "linux".equalsIgnoreCase(file.os()) && name.endsWith(".tar.gz");
            };
        }
    }

    static Package packageFor(TargetPlatform system) throws ArtifactNotAvailableException {
        if (system.os() == TargetPlatform.Os.WINDOWS && system.arch() == TargetPlatform.Arch.X64) {
            return Package.WINDOWS_ZIP;
        }
        if (system.os() == TargetPlatform.Os.LINUX && system.arch() == TargetPlatform.Arch.X64
                && system.libc() == TargetPlatform.Libc.GLIBC) {
            return Package.LINUX_TAR_GZ;
        }
        throw new ArtifactNotAvailableException("A managed MariaDB server is not available for " + system
                + ". MariaDB publishes server archives only for Windows x64 and Linux x86_64 with glibc."
                + " Use the Docker mode or an external MariaDB/MySQL server instead.");
    }

    /** Rewrites the API's {@code http://} URL to HTTPS and pins the archive mirror. */
    static URI archiveMirror(URI url) throws MalformedResponseException {
        if (!"downloads.mariadb.org".equalsIgnoreCase(url.getHost())) {
            throw new MalformedResponseException("MariaDB file URL on unexpected host: " + url);
        }
        if (!"http".equalsIgnoreCase(url.getScheme()) && !"https".equalsIgnoreCase(url.getScheme())) {
            throw new MalformedResponseException("MariaDB file URL with unexpected scheme: " + url);
        }
        try {
            return new URI("https", null, url.getHost(), -1, url.getPath(), MIRROR_QUERY, null);
        } catch (URISyntaxException e) {
            throw new MalformedResponseException("MariaDB file URL cannot be rewritten: " + url, e);
        }
    }

    private static Release single(List<Release> releases, String wanted) throws ArtifactNotAvailableException {
        if (releases.isEmpty()) {
            throw new ArtifactNotAvailableException("MariaDB lists no release for " + wanted);
        }
        return releases.get(0);
    }

    /** One release series from the top-level list. */
    record Series(String id, String status, String supportType) {
    }

    static List<Series> parseSeries(byte[] body) throws MalformedResponseException {
        String context = "MariaDB series";
        JsonNode root = Json.object(Json.parse(body, context), context);
        List<Series> series = new ArrayList<>();
        for (JsonNode node : Json.array(root, "major_releases", context)) {
            String c = context + ".major_releases[]";
            Json.object(node, c);
            series.add(new Series(Json.string(node, "release_id", c), Json.optString(node, "release_status", c).orElse(""),
                    Json.optString(node, "release_support_type", c).orElse("")));
        }
        return series;
    }

    static String newestLtsSeries(List<Series> series) throws ArtifactNotAvailableException {
        return series.stream()
                .filter(s -> s.status().equalsIgnoreCase("Stable") && s.supportType().equalsIgnoreCase("Long Term Support"))
                .filter(s -> SERIES.matcher(s.id()).matches())
                .map(Series::id)
                .findFirst()
                .orElseThrow(() -> new ArtifactNotAvailableException("MariaDB lists no stable long-term-support series"));
    }

    /** One downloadable file of a release. */
    record ReleaseFile(String name, String os, String cpu, URI url, Map<HashAlgorithm, Checksum> checksums) {
    }

    /** One release. */
    record Release(String id, List<ReleaseFile> files) {
    }

    /** Parses {@code /{series}/latest/} (key {@code releases}) or {@code /{release}/} (key {@code release_data}). */
    static List<Release> parseReleases(byte[] body) throws MalformedResponseException {
        String context = "MariaDB releases";
        JsonNode root = Json.object(Json.parse(body, context), context);
        JsonNode map = root.has("releases") ? root.get("releases") : root.get("release_data");
        Json.object(map, context + ".releases");
        List<Release> releases = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : map.properties()) {
            String c = context + "." + entry.getKey();
            JsonNode release = Json.object(entry.getValue(), c);
            String id = Json.string(release, "release_id", c);
            List<ReleaseFile> files = new ArrayList<>();
            for (JsonNode file : Json.array(release, "files", c)) {
                String fc = c + ".files[]";
                Json.object(file, fc);
                Optional<String> url = Json.optString(file, "file_download_url", fc);
                if (url.isEmpty()) {
                    continue;
                }
                Map<HashAlgorithm, Checksum> checksums = new EnumMap<>(HashAlgorithm.class);
                JsonNode sums = file.get("checksum");
                if (sums != null && !sums.isNull()) {
                    for (Map.Entry<String, JsonNode> sum : Json.object(sums, fc + ".checksum").properties()) {
                        Optional<HashAlgorithm> algorithm = HashAlgorithm.fromId(sum.getKey());
                        if (algorithm.isEmpty() || sum.getValue() == null || sum.getValue().isNull()) {
                            continue;
                        }
                        checksums.put(algorithm.get(), Checksum.parse(algorithm.get(),
                                Json.string(sum.getValue(), fc + ".checksum." + sum.getKey()))
                                .orElseThrow(() -> new MalformedResponseException(fc + ": invalid " + sum.getKey())));
                    }
                }
                files.add(new ReleaseFile(Json.string(file, "file_name", fc),
                        Json.optString(file, "os", fc).orElse(""), Json.optString(file, "cpu", fc).orElse(""),
                        Json.url(url.get(), fc + ".file_download_url"), checksums));
            }
            releases.add(new Release(id, files));
        }
        return releases;
    }
}
