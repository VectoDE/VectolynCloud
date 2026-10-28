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
import net.vectora.cloud.download.http.DownloadClient;
import net.vectora.cloud.download.http.HostAllowlist;
import tools.jackson.databind.JsonNode;

/**
 * Fabric-Meta ({@code https://meta.fabricmc.net/v2}): the Fabric server launcher jar for one
 * Minecraft version, loader version and installer version.
 *
 * <p>Reads from the request: {@code gameVersion} (or {@code version}) for Minecraft, the options
 * {@code loader} and {@code installer} (newest stable if empty). Fabric publishes no checksum for
 * the launcher, so the result has none: the downloader pins the hash on first use (TOFU).
 */
public final class FabricMetaProvider implements ArtifactProvider {

    /** Provider id. */
    public static final String ID = "fabric";

    /** Option name for the loader version. */
    public static final String OPTION_LOADER = "loader";

    /** Option name for the installer version. */
    public static final String OPTION_INSTALLER = "installer";

    static final String API = "https://meta.fabricmc.net/v2/versions/";

    private static final HostAllowlist HOSTS = HostAllowlist.of("meta.fabricmc.net");

    private final DownloadClient client;

    /**
     * Creates the provider.
     *
     * @param client the HTTP client
     */
    public FabricMetaProvider(DownloadClient client) {
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
        String game = request.gameVersionOpt().or(request::versionOpt).orElseThrow(() ->
                new IllegalArgumentException("Fabric needs the Minecraft version (gameVersion)"));
        String g = ApiSupport.segment(game, "gameVersion");

        String loader = request.option(OPTION_LOADER).isPresent()
                ? request.option(OPTION_LOADER).get()
                : newestStable(parseComponents(ApiSupport.fetchJson(client, URI.create(API + "loader"), HOSTS,
                        "Fabric loader list")), "loader");
        String installer = request.option(OPTION_INSTALLER).isPresent()
                ? request.option(OPTION_INSTALLER).get()
                : newestStable(parseComponents(ApiSupport.fetchJson(client, URI.create(API + "installer"), HOSTS,
                        "Fabric installer list")), "installer");
        String l = request.option(OPTION_LOADER).isPresent()
                ? ApiSupport.segment(loader, "loader")
                : ApiSupport.apiSegment(loader, "Fabric loader version");
        String i = request.option(OPTION_INSTALLER).isPresent()
                ? ApiSupport.segment(installer, "installer")
                : ApiSupport.apiSegment(installer, "Fabric installer version");

        // Fails with 400 if the loader does not support this Minecraft version.
        String confirmed = parseLoaderForGame(ApiSupport.fetchJson(client, URI.create(API + "loader/" + g + "/" + l),
                HOSTS, "Fabric loader " + loader + " for Minecraft " + game));
        if (!confirmed.equals(loader)) {
            throw new MalformedResponseException("Fabric-Meta answered with loader " + confirmed + " for " + loader);
        }

        URI url = URI.create(API + "loader/" + g + "/" + l + "/" + i + "/server/jar");
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("loader", loader);
        metadata.put("installer", installer);
        return new ResolvedArtifact(
                new ArtifactCoordinate(ID, request.project(), game, "loader-" + loader + "+installer-" + installer),
                url, "fabric-server-mc." + game + "-loader." + loader + "-launcher." + installer + ".jar",
                Optional.empty(), OptionalLong.empty(), OptionalInt.empty(), metadata);
    }

    private static String newestStable(List<Component> components, String what) throws ArtifactNotAvailableException {
        return components.stream().filter(Component::stable).map(Component::version).findFirst()
                .orElseThrow(() -> new ArtifactNotAvailableException("Fabric-Meta lists no stable " + what));
    }

    /** One loader or installer version. */
    record Component(String version, boolean stable) {
    }

    /** Parses {@code /v2/versions/loader} or {@code /v2/versions/installer}; newest first. */
    static List<Component> parseComponents(byte[] body) throws MalformedResponseException {
        String context = "Fabric-Meta versions";
        List<Component> components = new ArrayList<>();
        for (JsonNode node : Json.array(Json.parse(body, context), context)) {
            Json.object(node, context + "[]");
            components.add(new Component(Json.string(node, "version", context + "[]"),
                    Json.bool(node, "stable", false, context + "[]")));
        }
        return components;
    }

    /** Parses {@code /v2/versions/loader/{game}/{loader}} and returns the loader version. */
    static String parseLoaderForGame(byte[] body) throws MalformedResponseException {
        String context = "Fabric-Meta loader";
        JsonNode root = Json.object(Json.parse(body, context), context);
        return Json.string(Json.object(root, "loader", context), "version", context + ".loader");
    }
}
