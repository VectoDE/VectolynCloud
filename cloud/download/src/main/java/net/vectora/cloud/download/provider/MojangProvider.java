package net.vectora.cloud.download.provider;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import net.vectora.cloud.download.ArtifactNotAvailableException;
import net.vectora.cloud.download.ChecksumMismatchException;
import net.vectora.cloud.download.MalformedResponseException;
import net.vectora.cloud.download.hash.Checksum;
import net.vectora.cloud.download.hash.HashAlgorithm;
import net.vectora.cloud.download.hash.MultiDigest;
import net.vectora.cloud.download.http.DownloadClient;
import net.vectora.cloud.download.http.HostAllowlist;
import tools.jackson.databind.JsonNode;

/**
 * Mojang's version manifest: the vanilla server jar and, more important for the cloud, the Java
 * version each Minecraft version needs ({@code javaVersion.majorVersion}).
 *
 * <p>Reads from the request: {@code version} (Minecraft version; newest release if empty). The
 * version JSON is checked against the SHA-1 the manifest lists for it. Mojang publishes only SHA-1
 * for the server jar, so that is the checksum of the result.
 */
public final class MojangProvider implements ArtifactProvider {

    /** Provider id. */
    public static final String ID = "mojang";

    static final URI MANIFEST = URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");

    private static final HostAllowlist HOSTS = HostAllowlist.of("piston-meta.mojang.com", "piston-data.mojang.com",
            "launcher.mojang.com", "launchermeta.mojang.com");

    private final DownloadClient client;

    /**
     * Creates the provider.
     *
     * @param client the HTTP client
     */
    public MojangProvider(DownloadClient client) {
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

    /**
     * Returns the Java major version a Minecraft version needs.
     *
     * @param minecraftVersion the Minecraft version, for example {@code 26.3}
     * @return the Java major version, for example 25
     * @throws IOException if the API fails or the version is unknown
     * @throws InterruptedException if interrupted
     */
    public int javaMajorVersion(String minecraftVersion) throws IOException, InterruptedException {
        VersionDetails details = details(minecraftVersion);
        return details.javaMajor().orElseThrow(() -> new ArtifactNotAvailableException(
                "Mojang lists no Java version for Minecraft " + minecraftVersion));
    }

    @Override
    public ResolvedArtifact resolve(ArtifactRequest request) throws IOException, InterruptedException {
        VersionDetails details = details(request.version());
        ServerDownload server = details.server().orElseThrow(() -> new ArtifactNotAvailableException(
                "Mojang has no server download for Minecraft " + details.id()));
        ApiSupport.checkDownloadUrl(server.url(), HOSTS, "Mojang server " + details.id());
        return new ResolvedArtifact(new ArtifactCoordinate(ID, request.project(), details.id(), null),
                server.url(), ApiSupport.fileName("minecraft_server." + details.id() + ".jar", ID), Optional.of(server.sha1()),
                server.size() >= 0 ? OptionalLong.of(server.size()) : OptionalLong.empty(),
                details.javaMajor(), Map.of());
    }

    private VersionDetails details(String wanted) throws IOException, InterruptedException {
        Manifest manifest = parseManifest(ApiSupport.fetchJson(client, MANIFEST, HOSTS, "Mojang version manifest"));
        String id = wanted == null ? manifest.latestRelease() : wanted;
        ManifestEntry entry = manifest.find(id).orElseThrow(() -> new ArtifactNotAvailableException(
                "Mojang's manifest has no Minecraft version " + id));
        ApiSupport.checkDownloadUrl(entry.url(), HOSTS, "Mojang version " + id);
        byte[] body = ApiSupport.fetchJson(client, entry.url(), HOSTS, "Mojang version " + id);
        Checksum actual = MultiDigest.of(body, Set.of(HashAlgorithm.SHA1)).get(HashAlgorithm.SHA1);
        if (!actual.matches(entry.sha1())) {
            throw new ChecksumMismatchException("Mojang version JSON for " + id + ": manifest lists " + entry.sha1()
                    + ", received " + actual);
        }
        return parseVersion(body);
    }

    /** One entry of the manifest. */
    record ManifestEntry(String id, String type, URI url, Checksum sha1) {
    }

    /** The manifest. */
    record Manifest(String latestRelease, Map<String, ManifestEntry> versions) {

        Optional<ManifestEntry> find(String id) {
            return Optional.ofNullable(versions.get(id));
        }
    }

    static Manifest parseManifest(byte[] body) throws MalformedResponseException {
        String context = "Mojang manifest";
        JsonNode root = Json.object(Json.parse(body, context), context);
        String latest = Json.string(Json.object(root, "latest", context), "release", context + ".latest");
        Map<String, ManifestEntry> versions = new java.util.LinkedHashMap<>();
        for (JsonNode version : Json.array(root, "versions", context)) {
            String c = context + ".versions[]";
            Json.object(version, c);
            String id = Json.string(version, "id", c);
            String sha1 = Json.string(version, "sha1", c);
            versions.putIfAbsent(id, new ManifestEntry(id, Json.optString(version, "type", c).orElse(""),
                    Json.url(Json.string(version, "url", c), c + ".url"),
                    Checksum.parse(HashAlgorithm.SHA1, sha1)
                            .orElseThrow(() -> new MalformedResponseException(c + ": invalid sha1"))));
        }
        return new Manifest(latest, versions);
    }

    /** The server download of a version. */
    record ServerDownload(URI url, Checksum sha1, long size) {
    }

    /** The parts of a version JSON the cloud needs. */
    record VersionDetails(String id, OptionalInt javaMajor, Optional<ServerDownload> server) {
    }

    static VersionDetails parseVersion(byte[] body) throws MalformedResponseException {
        String context = "Mojang version";
        JsonNode root = Json.object(Json.parse(body, context), context);
        String id = Json.string(root, "id", context);
        OptionalInt javaMajor = OptionalInt.empty();
        JsonNode java = root.get("javaVersion");
        if (java != null && !java.isNull()) {
            javaMajor = OptionalInt.of(Json.smallInt(Json.object(java, context + ".javaVersion").get("majorVersion"),
                    context + ".javaVersion.majorVersion"));
        }
        Optional<ServerDownload> server = Optional.empty();
        JsonNode downloads = root.get("downloads");
        if (downloads != null && !downloads.isNull()) {
            JsonNode serverNode = Json.object(downloads, context + ".downloads").get("server");
            if (serverNode != null && !serverNode.isNull()) {
                String c = context + ".downloads.server";
                Json.object(serverNode, c);
                server = Optional.of(new ServerDownload(Json.url(Json.string(serverNode, "url", c), c + ".url"),
                        Checksum.parse(HashAlgorithm.SHA1, Json.string(serverNode, "sha1", c))
                                .orElseThrow(() -> new MalformedResponseException(c + ": invalid sha1")),
                        serverNode.has("size") ? Json.integer(serverNode, "size", c) : -1));
            }
        }
        return new VersionDetails(id, javaMajor, server);
    }
}
