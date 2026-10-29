package net.vectora.cloud.download.provider;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import net.vectora.cloud.download.ArtifactNotAvailableException;
import net.vectora.cloud.download.MalformedResponseException;
import net.vectora.cloud.download.hash.Checksum;
import net.vectora.cloud.download.hash.HashAlgorithm;
import net.vectora.cloud.download.http.DownloadClient;
import net.vectora.cloud.download.http.HostAllowlist;
import tools.jackson.databind.JsonNode;

/**
 * Download APIs in the "bibliothek v2" style, which GeyserMC and PandaSpigot both run:
 * {@code /v2/projects/{p}}, {@code /versions/{v}}, {@code /builds/{b}}, {@code /downloads/{key}}.
 *
 * <p>Reads from the request: {@code project}, {@code version} (last listed version if empty),
 * {@code build} (highest build number if empty; the list is not sorted, so the maximum is taken)
 * and {@code platform} as the download key (default: {@link #defaultDownload}). The result
 * carries the SHA-256 of the build.
 */
public final class BibliothekProvider implements ArtifactProvider {

    /** Provider id of the GeyserMC download API. */
    public static final String GEYSER_ID = "geysermc";

    /** Provider id of the PandaSpigot download API. */
    public static final String PANDASPIGOT_ID = "pandaspigot";

    private final String id;
    private final String api;
    private final HostAllowlist hosts;
    private final String defaultDownload;
    private final DownloadClient client;

    private BibliothekProvider(String id, String api, HostAllowlist hosts, String defaultDownload,
            DownloadClient client) {
        this.id = id;
        this.api = api;
        this.hosts = hosts;
        this.defaultDownload = defaultDownload;
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * GeyserMC ({@code https://download.geysermc.org/v2}): Geyser and Floodgate. The download key
     * ({@code velocity}, {@code spigot}, ...) must be given as {@code platform}.
     *
     * @param client the HTTP client
     * @return the provider
     */
    public static BibliothekProvider geyser(DownloadClient client) {
        return new BibliothekProvider(GEYSER_ID, "https://download.geysermc.org/v2/projects/",
                HostAllowlist.of("download.geysermc.org"), null, client);
    }

    /**
     * PandaSpigot ({@code https://downloads.hpfxd.com/v2}), the 1.8.8 fork. Download key {@code paperclip}.
     *
     * @param client the HTTP client
     * @return the provider
     */
    public static BibliothekProvider pandaSpigot(DownloadClient client) {
        return new BibliothekProvider(PANDASPIGOT_ID, "https://downloads.hpfxd.com/v2/projects/",
                HostAllowlist.of("downloads.hpfxd.com"), "paperclip", client);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public HostAllowlist allowedHosts() {
        return hosts;
    }

    @Override
    public ResolvedArtifact resolve(ArtifactRequest request) throws IOException, InterruptedException {
        String project = ApiSupport.segment(request.project(), "project");
        String key = request.platformOpt().orElse(defaultDownload);
        if (key == null) {
            throw new IllegalArgumentException(id + " needs the download key as platform, for example velocity");
        }
        String downloadKey = ApiSupport.segment(key, "platform");

        String version = request.version();
        if (version == null) {
            List<String> versions = parseProject(ApiSupport.fetchJson(client, URI.create(api + project), hosts,
                    id + " project " + project));
            if (versions.isEmpty()) {
                throw new ArtifactNotAvailableException(id + " lists no version of " + project);
            }
            version = versions.get(versions.size() - 1);
        }
        String v = request.versionOpt().isPresent()
                ? ApiSupport.segment(version, "version")
                : ApiSupport.apiSegment(version, id + " version");
        String versionBase = api + project + "/versions/" + v;

        long build;
        if (request.buildOpt().isPresent()) {
            build = parseBuildNumber(request.build());
        } else {
            List<Long> builds = parseVersionBuilds(ApiSupport.fetchJson(client, URI.create(versionBase), hosts,
                    id + " " + project + " " + version));
            String label = id + " has no build of " + project + " " + version;
            build = builds.stream().mapToLong(Long::longValue).max().orElseThrow(() ->
                    new ArtifactNotAvailableException(label));
        }

        Build details = parseBuild(ApiSupport.fetchJson(client, URI.create(versionBase + "/builds/" + build), hosts,
                id + " " + project + " " + version + " #" + build));
        Download download = details.downloads().get(key);
        if (download == null) {
            throw new ArtifactNotAvailableException(id + " " + project + " " + version + " #" + build
                    + " has no download '" + key + "', available: " + details.downloads().keySet());
        }
        URI url = URI.create(versionBase + "/builds/" + build + "/downloads/" + downloadKey);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("download", key);
        return new ResolvedArtifact(new ArtifactCoordinate(id, project, version, Long.toString(build)), url,
                ApiSupport.fileName(download.name(), id), Optional.of(download.sha256()), OptionalLong.empty(), OptionalInt.empty(), metadata);
    }

    private static long parseBuildNumber(String build) {
        try {
            long value = Long.parseLong(build);
            if (value < 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("build must be a non-negative number: " + build, e);
        }
    }

    /** Versions as listed by {@code /v2/projects/{p}}. */
    static List<String> parseProject(byte[] body) throws MalformedResponseException {
        String context = "bibliothek project";
        JsonNode root = Json.object(Json.parse(body, context), context);
        List<String> versions = new ArrayList<>();
        for (JsonNode version : Json.array(root, "versions", context)) {
            versions.add(Json.string(version, context + ".versions[]"));
        }
        return versions;
    }

    /** Build numbers as listed by {@code /versions/{v}}, in the API's (unsorted) order. */
    static List<Long> parseVersionBuilds(byte[] body) throws MalformedResponseException {
        String context = "bibliothek version";
        JsonNode root = Json.object(Json.parse(body, context), context);
        List<Long> builds = new ArrayList<>();
        for (JsonNode build : Json.array(root, "builds", context)) {
            long number = Json.integer(build, context + ".builds[]");
            if (number < 0) {
                throw new MalformedResponseException(context + ".builds[]: negative build number");
            }
            builds.add(number);
        }
        return builds;
    }

    /** One download of a build. */
    record Download(String name, Checksum sha256) {
    }

    /** One build. */
    record Build(long build, Map<String, Download> downloads) {
    }

    static Build parseBuild(byte[] body) throws MalformedResponseException {
        String context = "bibliothek build";
        JsonNode root = Json.object(Json.parse(body, context), context);
        long build = Json.integer(root, "build", context);
        Map<String, Download> downloads = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : Json.object(root, "downloads", context).properties()) {
            String c = context + ".downloads." + entry.getKey();
            JsonNode download = Json.object(entry.getValue(), c);
            Checksum sha256 = Checksum.parse(HashAlgorithm.SHA256, Json.string(download, "sha256", c))
                    .orElseThrow(() -> new MalformedResponseException(c + ": invalid sha256"));
            downloads.put(entry.getKey(), new Download(Json.string(download, "name", c), sha256));
        }
        return new Build(build, downloads);
    }
}
