package net.vectolyn.cloud.download.provider;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 * Modrinth ({@code https://api.modrinth.com/v2}): plugins and mods such as the Via* family,
 * Lithium, FerriteCore, ServerCore, FabricProxy-Lite, Fabric API and Floodgate-Fabric.
 *
 * <p>Reads from the request: {@code project} (slug), {@code platform} (loader, required),
 * {@code gameVersion} (filter, optional), {@code version} (exact {@code version_number} or version
 * id; newest if empty) and {@code channel} ({@code release} by default; {@code beta} also allows
 * releases, {@code alpha} allows everything). The result carries the strongest file hash, SHA-512.
 */
public final class ModrinthProvider implements ArtifactProvider {

    /** Provider id. */
    public static final String ID = "modrinth";

    static final String API = "https://api.modrinth.com/v2/project/";

    private static final HostAllowlist HOSTS = HostAllowlist.of("api.modrinth.com", "cdn.modrinth.com");
    private static final List<String> CHANNELS = List.of("release", "beta", "alpha");

    private final DownloadClient client;

    /**
     * Creates the provider.
     *
     * @param client the HTTP client
     */
    public ModrinthProvider(DownloadClient client) {
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
        String loader = request.platformOpt().orElseThrow(() ->
                new IllegalArgumentException("Modrinth needs the loader as platform, for example velocity"));
        ApiSupport.segment(loader, "platform");
        String channel = request.channelOpt().orElse("release").toLowerCase(Locale.ROOT);
        int rank = CHANNELS.indexOf(channel);
        if (rank < 0) {
            throw new IllegalArgumentException("Modrinth channel must be one of " + CHANNELS + ": " + channel);
        }

        Map<String, String> query = new LinkedHashMap<>();
        query.put("loaders", "[\"" + loader + "\"]");
        request.gameVersionOpt().ifPresent(game -> query.put("game_versions",
                "[\"" + ApiSupport.segment(game, "gameVersion") + "\"]"));
        query.put("include_changelog", "false");
        URI uri = URI.create(API + slug + "/version" + ApiSupport.query(query));
        List<ModrinthVersion> versions = parseVersions(ApiSupport.fetchJson(client, uri, HOSTS, "Modrinth project " + slug));

        String what = "Modrinth " + slug + " for " + loader
                + request.gameVersionOpt().map(g -> " and Minecraft " + g).orElse("");
        ModrinthVersion chosen;
        if (request.versionOpt().isPresent()) {
            chosen = versions.stream()
                    .filter(v -> v.versionNumber().equals(request.version()) || v.id().equals(request.version()))
                    .findFirst()
                    .orElseThrow(() -> new ArtifactNotAvailableException(what + " has no version " + request.version()));
        } else {
            chosen = versions.stream()
                    .filter(v -> {
                        int r = CHANNELS.indexOf(v.versionType());
                        return r >= 0 && r <= rank;
                    })
                    .max(Comparator.comparing(ModrinthVersion::published))
                    .orElseThrow(() -> new ArtifactNotAvailableException(what + " has no " + channel + " version"));
        }

        ModrinthFile file = chosen.files().stream().filter(ModrinthFile::primary).findFirst()
                .orElseGet(() -> chosen.files().isEmpty() ? null : chosen.files().get(0));
        if (file == null) {
            throw new ArtifactNotAvailableException(what + " version " + chosen.versionNumber() + " has no file");
        }
        Checksum checksum = Checksum.strongest(file.hashes().values()).orElseThrow(() ->
                new ArtifactNotAvailableException(what + " version " + chosen.versionNumber() + " has no checksum"));
        ApiSupport.checkDownloadUrl(file.url(), HOSTS, what);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("version-id", chosen.id());
        metadata.put("version-type", chosen.versionType());
        metadata.put("loader", loader);
        return new ResolvedArtifact(new ArtifactCoordinate(ID, request.project(), chosen.versionNumber(), null),
                file.url(), ApiSupport.fileName(file.filename(), ID), java.util.Optional.of(checksum),
                file.size() >= 0 ? OptionalLong.of(file.size()) : OptionalLong.empty(), OptionalInt.empty(), metadata);
    }

    /** One file of a version. */
    record ModrinthFile(URI url, String filename, boolean primary, long size, Map<HashAlgorithm, Checksum> hashes) {
    }

    /** One version of a project. */
    record ModrinthVersion(String id, String versionNumber, String versionType, Instant published,
            List<ModrinthFile> files) {
    }

    static List<ModrinthVersion> parseVersions(byte[] body) throws MalformedResponseException {
        String context = "Modrinth versions";
        List<ModrinthVersion> versions = new ArrayList<>();
        for (JsonNode node : Json.array(Json.parse(body, context), context)) {
            String c = context + "[]";
            Json.object(node, c);
            String published = Json.string(node, "date_published", c);
            Instant instant;
            try {
                instant = Instant.parse(published);
            } catch (DateTimeParseException e) {
                throw new MalformedResponseException(c + ".date_published: not a timestamp", e);
            }
            List<ModrinthFile> files = new ArrayList<>();
            for (JsonNode fileNode : Json.array(node, "files", c)) {
                String fc = c + ".files[]";
                Json.object(fileNode, fc);
                Map<HashAlgorithm, Checksum> hashes = new EnumMap<>(HashAlgorithm.class);
                JsonNode hashNode = fileNode.get("hashes");
                if (hashNode != null && !hashNode.isNull()) {
                    for (Map.Entry<String, JsonNode> hash : Json.object(hashNode, fc + ".hashes").properties()) {
                        var algorithm = HashAlgorithm.fromId(hash.getKey());
                        if (algorithm.isPresent()) {
                            Checksum checksum = Checksum.parse(algorithm.get(),
                                    Json.string(hash.getValue(), fc + ".hashes." + hash.getKey()))
                                    .orElseThrow(() -> new MalformedResponseException(fc + ".hashes: invalid "
                                            + hash.getKey()));
                            hashes.put(algorithm.get(), checksum);
                        }
                    }
                }
                files.add(new ModrinthFile(Json.url(Json.string(fileNode, "url", fc), fc + ".url"),
                        Json.string(fileNode, "filename", fc), Json.bool(fileNode, "primary", false, fc),
                        fileNode.has("size") ? Json.integer(fileNode, "size", fc) : -1, hashes));
            }
            versions.add(new ModrinthVersion(Json.string(node, "id", c), Json.string(node, "version_number", c),
                    Json.string(node, "version_type", c).toLowerCase(Locale.ROOT), instant, files));
        }
        return versions;
    }
}
