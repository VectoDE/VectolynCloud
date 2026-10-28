package net.vectora.cloud.download.provider;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * PaperMC Fill v3 ({@code https://fill.papermc.io/v3}): Paper, Velocity and the other PaperMC projects.
 *
 * <p>Reads from the request: {@code project}, {@code version} (newest version with a build in
 * the channel if empty), {@code build} (newest build in the channel if empty), {@code channel}
 * (default {@code STABLE}). The result carries the SHA-256 and {@code java.version.minimum}
 * of the version.
 */
public final class PaperFillProvider implements ArtifactProvider {

    /** Provider id. */
    public static final String ID = "papermc";

    static final String API = "https://fill.papermc.io/v3/projects/";
    static final String DEFAULT_CHANNEL = "STABLE";
    static final String DOWNLOAD_KEY = "server:default";
    private static final int MAX_VERSIONS_TRIED = 10;

    private static final HostAllowlist HOSTS = HostAllowlist.of("fill.papermc.io", "fill-data.papermc.io");

    private final DownloadClient client;

    /**
     * Creates the provider.
     *
     * @param client the HTTP client
     */
    public PaperFillProvider(DownloadClient client) {
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
        String project = ApiSupport.segment(request.project(), "project");
        String channel = request.channelOpt().orElse(DEFAULT_CHANNEL).toUpperCase(Locale.ROOT);
        String base = API + project + "/versions/";

        List<String> candidates;
        if (request.versionOpt().isPresent()) {
            candidates = List.of(request.version());
        } else {
            byte[] body = ApiSupport.fetchJson(client, URI.create(API + project), HOSTS, "Fill project " + project);
            candidates = parseProjectVersions(body);
        }

        int tried = 0;
        for (String version : candidates) {
            if (++tried > MAX_VERSIONS_TRIED) {
                break;
            }
            String v = request.versionOpt().isPresent()
                    ? ApiSupport.segment(version, "version")
                    : ApiSupport.apiSegment(version, "Fill project version");
            FillBuild build;
            if (request.buildOpt().isPresent()) {
                String b = ApiSupport.segment(request.build(), "build");
                build = parseBuild(ApiSupport.fetchJson(client, URI.create(base + v + "/builds/" + b), HOSTS,
                        "Fill build " + project + " " + version + " #" + request.build()));
            } else {
                List<FillBuild> builds = parseBuilds(ApiSupport.fetchJson(client,
                        URI.create(base + v + "/builds" + ApiSupport.query(Map.of("channel", channel))), HOSTS,
                        "Fill builds of " + project + " " + version));
                Optional<FillBuild> newest = builds.stream()
                        .filter(b -> b.channel().equalsIgnoreCase(channel))
                        .max((a, b) -> Long.compare(a.id(), b.id()));
                if (newest.isEmpty()) {
                    if (request.versionOpt().isPresent()) {
                        throw new ArtifactNotAvailableException("Fill has no " + channel + " build of "
                                + project + " " + version);
                    }
                    continue;
                }
                build = newest.get();
            }
            FillVersion info = parseVersion(ApiSupport.fetchJson(client, URI.create(base + v), HOSTS,
                    "Fill version " + project + " " + version));
            return toArtifact(project, info, build);
        }
        throw new ArtifactNotAvailableException("Fill has no " + channel + " build of " + project
                + " in its newest " + MAX_VERSIONS_TRIED + " versions");
    }

    private ResolvedArtifact toArtifact(String project, FillVersion version, FillBuild build)
            throws IOException {
        FillDownload download = build.downloads().get(DOWNLOAD_KEY);
        if (download == null) {
            throw new ArtifactNotAvailableException("Fill build " + project + " " + version.id() + " #" + build.id()
                    + " has no " + DOWNLOAD_KEY + " download");
        }
        ApiSupport.checkDownloadUrl(download.url(), HOSTS, "Fill " + project);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("channel", build.channel());
        return new ResolvedArtifact(
                new ArtifactCoordinate(ID, project, version.id(), Long.toString(build.id())),
                download.url(), ApiSupport.fileName(download.name(), ID), Optional.of(download.sha256()),
                download.size() >= 0 ? OptionalLong.of(download.size()) : OptionalLong.empty(),
                version.javaMinimum(), metadata);
    }

    /** Versions of a project, newest first, as Fill lists them. */
    static List<String> parseProjectVersions(byte[] body) throws MalformedResponseException {
        JsonNode root = Json.object(Json.parse(body, "Fill project"), "Fill project");
        JsonNode groups = Json.object(root, "versions", "Fill project");
        List<String> versions = new ArrayList<>();
        for (Map.Entry<String, JsonNode> group : groups.properties()) {
            for (JsonNode version : Json.array(group.getValue(), "Fill project.versions." + group.getKey())) {
                versions.add(Json.string(version, "Fill project.versions." + group.getKey()));
            }
        }
        return versions;
    }

    /** One Fill version with its Java minimum. */
    record FillVersion(String id, OptionalInt javaMinimum) {
    }

    static FillVersion parseVersion(byte[] body) throws MalformedResponseException {
        JsonNode root = Json.object(Json.parse(body, "Fill version"), "Fill version");
        JsonNode version = Json.object(root, "version", "Fill version");
        String id = Json.string(version, "id", "Fill version.version");
        OptionalInt minimum = OptionalInt.empty();
        JsonNode java = version.get("java");
        if (java != null && !java.isNull()) {
            JsonNode javaVersion = Json.object(java, "Fill version.java").get("version");
            if (javaVersion != null && !javaVersion.isNull()) {
                minimum = Json.optSmallInt(Json.object(javaVersion, "Fill version.java.version"), "minimum",
                        "Fill version.java.version");
            }
        }
        return new FillVersion(id, minimum);
    }

    /** One download of a build. */
    record FillDownload(String name, Checksum sha256, long size, URI url) {
    }

    /** One build. */
    record FillBuild(long id, String channel, Map<String, FillDownload> downloads) {
    }

    static List<FillBuild> parseBuilds(byte[] body) throws MalformedResponseException {
        List<FillBuild> builds = new ArrayList<>();
        for (JsonNode build : Json.array(Json.parse(body, "Fill builds"), "Fill builds")) {
            builds.add(parseBuild(build));
        }
        return builds;
    }

    static FillBuild parseBuild(byte[] body) throws MalformedResponseException {
        return parseBuild(Json.parse(body, "Fill build"));
    }

    private static FillBuild parseBuild(JsonNode node) throws MalformedResponseException {
        String context = "Fill build";
        Json.object(node, context);
        long id = Json.integer(node, "id", context);
        String channel = Json.string(node, "channel", context);
        Map<String, FillDownload> downloads = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : Json.object(node, "downloads", context).properties()) {
            String c = context + ".downloads." + entry.getKey();
            JsonNode download = Json.object(entry.getValue(), c);
            JsonNode checksums = Json.object(download, "checksums", c);
            String sha256 = Json.string(checksums, "sha256", c + ".checksums");
            Checksum checksum = Checksum.parse(HashAlgorithm.SHA256, sha256)
                    .orElseThrow(() -> new MalformedResponseException(c + ": invalid sha256"));
            long size = download.has("size") ? Json.integer(download, "size", c) : -1;
            downloads.put(entry.getKey(), new FillDownload(Json.string(download, "name", c), checksum, size,
                    Json.url(Json.string(download, "url", c), c + ".url")));
        }
        return new FillBuild(id, channel, downloads);
    }
}
