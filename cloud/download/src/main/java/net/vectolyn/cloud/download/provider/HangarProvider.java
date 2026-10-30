package net.vectolyn.cloud.download.provider;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import net.vectolyn.cloud.download.ArtifactNotAvailableException;
import net.vectolyn.cloud.download.MalformedResponseException;
import net.vectolyn.cloud.download.hash.Checksum;
import net.vectolyn.cloud.download.hash.HashAlgorithm;
import net.vectolyn.cloud.download.http.DownloadClient;
import net.vectolyn.cloud.download.http.HostAllowlist;
import tools.jackson.databind.JsonNode;

/**
 * Hangar ({@code https://hangar.papermc.io/api/v1}), PaperMC's plugin repository. An alternative
 * source for plugins that are also on Modrinth.
 *
 * <p>Reads from the request: {@code project} (slug), {@code platform} ({@code PAPER},
 * {@code VELOCITY} or {@code WATERFALL}, required), {@code gameVersion} (platform version filter),
 * {@code version} (version name; newest in the channel if empty) and {@code channel}
 * (default {@code Release}). Only files hosted by Hangar have a SHA-256; versions that point to an
 * external URL are refused.
 */
public final class HangarProvider implements ArtifactProvider {

    /** Provider id. */
    public static final String ID = "hangar";

    static final String API = "https://hangar.papermc.io/api/v1/projects/";

    private static final HostAllowlist HOSTS = HostAllowlist.of("hangar.papermc.io", "hangarcdn.papermc.io");

    private final DownloadClient client;

    /**
     * Creates the provider.
     *
     * @param client the HTTP client
     */
    public HangarProvider(DownloadClient client) {
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
        String slug = ApiSupport.segment(request.project(), "project");
        String platform = request.platformOpt().orElseThrow(() ->
                new IllegalArgumentException("Hangar needs the platform, for example PAPER")).toUpperCase(Locale.ROOT);
        ApiSupport.segment(platform, "platform");
        String what = "Hangar " + request.project() + " for " + platform;

        HangarVersion version;
        if (request.versionOpt().isPresent()) {
            String v = ApiSupport.segment(request.version(), "version");
            version = parseVersion(ApiSupport.fetchJson(client, URI.create(API + slug + "/versions/" + v), HOSTS,
                    what + " " + request.version()));
        } else {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("limit", "1");
            query.put("offset", "0");
            query.put("channel", request.channelOpt().orElse("Release"));
            query.put("platform", platform);
            request.gameVersionOpt().ifPresent(game -> query.put("platformVersion", game));
            List<HangarVersion> versions = parseVersionPage(ApiSupport.fetchJson(client,
                    URI.create(API + slug + "/versions" + ApiSupport.query(query)), HOSTS, what));
            if (versions.isEmpty()) {
                throw new ArtifactNotAvailableException(what + request.gameVersionOpt().map(g -> " " + g).orElse("")
                        + " has no version in channel " + query.get("channel"));
            }
            version = versions.get(0);
        }

        HangarDownload download = version.downloads().get(platform);
        if (download == null) {
            throw new ArtifactNotAvailableException(what + " " + version.name() + " has no download for " + platform);
        }
        if (download.url().isEmpty() || download.sha256().isEmpty()) {
            throw new ArtifactNotAvailableException(what + " " + version.name()
                    + " is only available from an external site without a checksum");
        }
        URI url = ApiSupport.checkDownloadUrl(download.url().get(), HOSTS, what);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("platform", platform);
        return new ResolvedArtifact(new ArtifactCoordinate(ID, request.project(), version.name(), null), url,
                ApiSupport.fileName(download.fileName(), ID), download.sha256(),
                download.size() >= 0 ? OptionalLong.of(download.size()) : OptionalLong.empty(),
                OptionalInt.empty(), metadata);
    }

    /** One platform download of a version. */
    record HangarDownload(String fileName, Optional<Checksum> sha256, long size, Optional<URI> url) {
    }

    /** One version. */
    record HangarVersion(String name, Map<String, HangarDownload> downloads) {
    }

    static List<HangarVersion> parseVersionPage(byte[] body) throws MalformedResponseException {
        String context = "Hangar versions";
        JsonNode root = Json.object(Json.parse(body, context), context);
        List<HangarVersion> versions = new java.util.ArrayList<>();
        for (JsonNode node : Json.array(root, "result", context)) {
            versions.add(parseVersion(node));
        }
        return versions;
    }

    static HangarVersion parseVersion(byte[] body) throws MalformedResponseException {
        return parseVersion(Json.parse(body, "Hangar version"));
    }

    private static HangarVersion parseVersion(JsonNode node) throws MalformedResponseException {
        String context = "Hangar version";
        Json.object(node, context);
        String name = Json.string(node, "name", context);
        Map<String, HangarDownload> downloads = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : Json.object(node, "downloads", context).properties()) {
            String c = context + ".downloads." + entry.getKey();
            JsonNode download = Json.object(entry.getValue(), c);
            Optional<String> rawUrl = Json.optString(download, "downloadUrl", c);
            Optional<URI> url = Optional.empty();
            if (rawUrl.isPresent()) {
                url = Optional.of(Json.url(rawUrl.get(), c + ".downloadUrl"));
            }
            JsonNode info = download.get("fileInfo");
            if (info == null || info.isNull()) {
                // External downloads come without file info: no name, no size, no checksum.
                downloads.put(entry.getKey().toUpperCase(Locale.ROOT),
                        new HangarDownload("", Optional.empty(), -1, url));
                continue;
            }
            Json.object(info, c + ".fileInfo");
            Optional<Checksum> sha256 = Optional.empty();
            Optional<String> hash = Json.optString(info, "sha256Hash", c + ".fileInfo");
            if (hash.isPresent()) {
                sha256 = Optional.of(Checksum.parse(HashAlgorithm.SHA256, hash.get())
                        .orElseThrow(() -> new MalformedResponseException(c + ": invalid sha256Hash")));
            }
            long size = info.has("sizeBytes") ? Json.integer(info, "sizeBytes", c + ".fileInfo") : -1;
            downloads.put(entry.getKey().toUpperCase(Locale.ROOT),
                    new HangarDownload(Json.string(info, "name", c + ".fileInfo"), sha256, size, url));
        }
        return new HangarVersion(name, downloads);
    }
}
