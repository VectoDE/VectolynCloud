package net.vectolyn.cloud.download.provider;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
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
 * Eclipse Temurin runtimes from the Adoptium API v3 ({@code https://api.adoptium.net/v3/}).
 *
 * <ul>
 *   <li>{@code project}: {@code temurin} (vendor {@code eclipse}, HotSpot).</li>
 *   <li>{@code version}: the Java feature version, {@code 8} to {@code 99}. Required.</li>
 *   <li>{@code system}: Windows x64 and aarch64, Linux x64 and aarch64 (glibc, or musl as
 *       {@code alpine-linux}), macOS x64 and aarch64. Windows gets the zip, the others the tar.gz.</li>
 *   <li>Option {@code image-type}: {@code jre} or {@code jdk}. Without it a JRE is preferred and a
 *       JDK is taken only when Adoptium has no JRE for the platform (Java 8 on macOS aarch64).</li>
 *   <li>Option {@code release}: an exact release such as {@code jdk-21.0.13+9}; without it the
 *       newest general-availability release of the feature version.</li>
 * </ul>
 *
 * <p>The checksum is the SHA-256 the API lists for the package. The download link points to the
 * GitHub release of {@code adoptium/temurin<N>-binaries}, which redirects to GitHub's asset host.
 * The coordinate is {@code adoptium:temurin:<openjdk version>#<os>-<arch>-<image type>}, so every
 * platform and image type is a different artifact.
 */
public final class AdoptiumProvider implements ArtifactProvider {

    /** Provider id. */
    public static final String ID = "adoptium";

    /** The only project: Eclipse Temurin. */
    public static final String PROJECT = "temurin";

    /** Option for the image type, {@code jre} or {@code jdk}. */
    public static final String OPTION_IMAGE_TYPE = "image-type";

    /** Option for an exact release name such as {@code jdk-21.0.13+9}. */
    public static final String OPTION_RELEASE = "release";

    static final String API = "https://api.adoptium.net/v3/assets/";

    private static final HostAllowlist HOSTS = HostAllowlist.of("api.adoptium.net", "github.com",
            "objects.githubusercontent.com", "release-assets.githubusercontent.com");
    private static final Pattern FEATURE = Pattern.compile("[1-9][0-9]?");
    private static final Pattern RELEASE = Pattern.compile("jdk-?[0-9][0-9A-Za-z.+_-]{0,63}");
    private static final int MIN_FEATURE = 8;

    private final DownloadClient client;

    /**
     * Creates the provider.
     *
     * @param client the HTTP client
     */
    public AdoptiumProvider(DownloadClient client) {
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
     * Builds the request for a Temurin runtime.
     *
     * @param feature the Java feature version, for example 21
     * @param system the platform
     * @return the request; a JRE is preferred
     */
    public static ArtifactRequest request(int feature, TargetPlatform system) {
        return ArtifactRequest.of(PROJECT).withVersion(Integer.toString(feature)).withSystem(system);
    }

    @Override
    public ResolvedArtifact resolve(ArtifactRequest request) throws IOException, InterruptedException {
        if (!PROJECT.equals(request.project())) {
            throw new IllegalArgumentException("Adoptium project must be " + PROJECT + ": " + request.project());
        }
        int feature = feature(request.version());
        String os = osName(request.system());
        String arch = archName(request.system());
        Optional<String> release = request.option(OPTION_RELEASE);
        if (release.isPresent() && !RELEASE.matcher(release.get()).matches()) {
            throw new IllegalArgumentException("Adoptium release must look like jdk-21.0.13+9: " + release.get());
        }
        Optional<String> imageType = request.option(OPTION_IMAGE_TYPE);
        List<String> imageTypes;
        if (imageType.isEmpty()) {
            imageTypes = List.of("jre", "jdk");
        } else if (imageType.get().equals("jre") || imageType.get().equals("jdk")) {
            imageTypes = List.of(imageType.get());
        } else {
            throw new IllegalArgumentException("Adoptium image-type must be jre or jdk: " + imageType.get());
        }

        for (String type : imageTypes) {
            Optional<Asset> asset = find(feature, os, arch, type, release.orElse(null));
            if (asset.isPresent()) {
                return toArtifact(asset.get(), request.project(), feature);
            }
        }
        throw new ArtifactNotAvailableException("Adoptium has no Temurin " + String.join(" or ", imageTypes) + " "
                + release.orElse(Integer.toString(feature)) + " for " + request.system());
    }

    private Optional<Asset> find(int feature, String os, String arch, String imageType, String release)
            throws IOException, InterruptedException {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("architecture", arch);
        query.put("heap_size", "normal");
        query.put("image_type", imageType);
        query.put("jvm_impl", "hotspot");
        query.put("os", os);
        query.put("project", "jdk");
        List<Asset> assets;
        String what = "Adoptium " + imageType + " " + (release == null ? feature : release) + " " + os + "/" + arch;
        if (release == null) {
            query.put("vendor", "eclipse");
            URI uri = URI.create(API + "latest/" + feature + "/hotspot" + ApiSupport.query(query));
            assets = parseLatest(ApiSupport.fetchJson(client, uri, HOSTS, what));
        } else {
            URI uri = URI.create(API + "release_name/eclipse/" + ApiSupport.segment(release, "release")
                    + ApiSupport.query(query));
            try {
                assets = parseRelease(ApiSupport.fetchJson(client, uri, HOSTS, what));
            } catch (ArtifactNotAvailableException e) {
                // The API answers 404 for a release without a matching binary.
                assets = List.of();
            }
        }
        String suffix = os.equals("windows") ? ".zip" : ".tar.gz";
        return assets.stream()
                .filter(a -> a.os().equals(os) && a.architecture().equals(arch) && a.imageType().equals(imageType))
                .filter(a -> a.jvmImpl().equals("hotspot") && a.heapSize().equals("normal"))
                .filter(a -> a.version().major() == feature)
                .filter(a -> a.name().toLowerCase(Locale.ROOT).endsWith(suffix))
                .max(Comparator.comparing(Asset::version, Version.ORDER));
    }

    private static ResolvedArtifact toArtifact(Asset asset, String project, int feature) throws IOException {
        URI url = ApiSupport.checkDownloadUrl(asset.link(), HOSTS, "Adoptium " + asset.name());
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("release", asset.releaseName());
        metadata.put("feature", Integer.toString(feature));
        metadata.put("image-type", asset.imageType());
        metadata.put("os", asset.os());
        metadata.put("arch", asset.architecture());
        return new ResolvedArtifact(
                new ArtifactCoordinate(ID, project, asset.version().openjdkVersion(),
                        asset.os() + "-" + asset.architecture() + "-" + asset.imageType()),
                url, ApiSupport.fileName(asset.name(), ID), Optional.of(asset.sha256()),
                asset.size() > 0 ? OptionalLong.of(asset.size()) : OptionalLong.empty(), OptionalInt.empty(), metadata);
    }

    static int feature(String version) {
        if (version == null || !FEATURE.matcher(version).matches() || Integer.parseInt(version) < MIN_FEATURE) {
            throw new IllegalArgumentException("Adoptium version must be a Java feature version from 8 to 99: " + version);
        }
        return Integer.parseInt(version);
    }

    /** The API's name for the operating system. */
    static String osName(TargetPlatform system) throws ArtifactNotAvailableException {
        return switch (system.os()) {
            case WINDOWS -> "windows";
            case MAC -> "mac";
            case LINUX -> system.libc() == TargetPlatform.Libc.MUSL ? "alpine-linux" : "linux";
            case OTHER -> throw new ArtifactNotAvailableException("Adoptium has no Temurin builds for " + system
                    + ". Supported are Windows, Linux and macOS on x64 and aarch64; install Java yourself.");
        };
    }

    /** The API's name for the CPU architecture. */
    static String archName(TargetPlatform system) throws ArtifactNotAvailableException {
        return switch (system.arch()) {
            case X64 -> "x64";
            case AARCH64 -> "aarch64";
            case OTHER -> throw new ArtifactNotAvailableException("Adoptium has no Temurin builds for " + system
                    + ". Supported are Windows, Linux and macOS on x64 and aarch64; install Java yourself.");
        };
    }

    /** The version of a release. */
    record Version(int major, int minor, int security, int patch, int build, String openjdkVersion) {

        static final Comparator<Version> ORDER = Comparator.comparingInt(Version::major)
                .thenComparingInt(Version::minor).thenComparingInt(Version::security)
                .thenComparingInt(Version::patch).thenComparingInt(Version::build);
    }

    /** One downloadable package of a release. */
    record Asset(String releaseName, Version version, String os, String architecture, String imageType,
            String jvmImpl, String heapSize, String name, URI link, Checksum sha256, long size) {
    }

    /** Parses {@code /v3/assets/latest/{feature}/hotspot}: an array of {@code {binary, release_name, version}}. */
    static List<Asset> parseLatest(byte[] body) throws MalformedResponseException {
        String context = "Adoptium latest";
        List<Asset> assets = new ArrayList<>();
        for (JsonNode item : Json.array(Json.parse(body, context), context)) {
            String c = context + "[]";
            Json.object(item, c);
            String releaseName = Json.string(item, "release_name", c);
            Version version = parseVersion(Json.object(item, "version", c), c + ".version");
            assets.add(parseBinary(Json.object(item, "binary", c), releaseName, version, c + ".binary"));
        }
        return assets;
    }

    /** Parses {@code /v3/assets/release_name/eclipse/{release}}: one release with {@code binaries}. */
    static List<Asset> parseRelease(byte[] body) throws MalformedResponseException {
        String context = "Adoptium release";
        JsonNode root = Json.object(Json.parse(body, context), context);
        String releaseName = Json.string(root, "release_name", context);
        Version version = parseVersion(Json.object(root, "version_data", context), context + ".version_data");
        List<Asset> assets = new ArrayList<>();
        for (JsonNode binary : Json.array(root, "binaries", context)) {
            assets.add(parseBinary(Json.object(binary, context + ".binaries[]"), releaseName, version,
                    context + ".binaries[]"));
        }
        return assets;
    }

    private static Version parseVersion(JsonNode node, String context) throws MalformedResponseException {
        return new Version(Json.smallInt(node.get("major"), context + ".major"),
                optInt(node, "minor", context), optInt(node, "security", context), optInt(node, "patch", context),
                optInt(node, "build", context), Json.string(node, "openjdk_version", context));
    }

    private static int optInt(JsonNode node, String field, String context) throws MalformedResponseException {
        return Json.optSmallInt(node, field, context).orElse(0);
    }

    private static Asset parseBinary(JsonNode binary, String releaseName, Version version, String context)
            throws MalformedResponseException {
        JsonNode pkg = Json.object(binary, "package", context);
        String pc = context + ".package";
        String checksum = Json.string(pkg, "checksum", pc);
        Checksum sha256 = Checksum.parse(HashAlgorithm.SHA256, checksum)
                .orElseThrow(() -> new MalformedResponseException(pc + ".checksum: not a SHA-256"));
        long size = pkg.has("size") ? Json.integer(pkg, "size", pc) : -1;
        return new Asset(releaseName, version, Json.string(binary, "os", context),
                Json.string(binary, "architecture", context), Json.string(binary, "image_type", context),
                Json.string(binary, "jvm_impl", context), Json.optString(binary, "heap_size", context).orElse("normal"),
                Json.string(pkg, "name", pc), Json.url(Json.string(pkg, "link", pc), pc + ".link"), sha256, size);
    }
}
