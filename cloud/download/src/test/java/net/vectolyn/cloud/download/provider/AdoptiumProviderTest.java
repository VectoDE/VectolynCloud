package net.vectolyn.cloud.download.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import net.vectolyn.cloud.download.ArtifactNotAvailableException;
import net.vectolyn.cloud.download.HostNotAllowedException;
import net.vectolyn.cloud.download.MalformedResponseException;
import net.vectolyn.cloud.download.hash.HashAlgorithm;
import net.vectolyn.cloud.download.http.FakeTransport;
import net.vectolyn.cloud.download.provider.TargetPlatform.Arch;
import net.vectolyn.cloud.download.provider.TargetPlatform.Libc;
import net.vectolyn.cloud.download.provider.TargetPlatform.Os;
import org.junit.jupiter.api.Test;

class AdoptiumProviderTest {

    private static final TargetPlatform LINUX_X64 = new TargetPlatform(Os.LINUX, Arch.X64, Libc.GLIBC);
    private static final TargetPlatform ALPINE_X64 = new TargetPlatform(Os.LINUX, Arch.X64, Libc.MUSL);
    private static final TargetPlatform WINDOWS_X64 = new TargetPlatform(Os.WINDOWS, Arch.X64, Libc.NONE);
    private static final TargetPlatform MAC_AARCH64 = new TargetPlatform(Os.MAC, Arch.AARCH64, Libc.NONE);

    static String latest(int feature, String os, String arch, String imageType) {
        return "https://api.adoptium.net/v3/assets/latest/" + feature + "/hotspot?architecture=" + arch
                + "&heap_size=normal&image_type=" + imageType + "&jvm_impl=hotspot&os=" + os + "&project=jdk&vendor=eclipse";
    }

    private static ResolvedArtifact resolve(FakeTransport transport, ArtifactRequest request) throws Exception {
        return new AdoptiumProvider(FakeTransport.client(transport)).resolve(request);
    }

    @Test
    void linuxJre21WithTheSha256FromTheApi() throws Exception {
        ResolvedArtifact artifact = resolve(new FakeTransport().okResource(latest(21, "linux", "x64", "jre"),
                "adoptium-latest-21-linux-x64-jre.json"), AdoptiumProvider.request(21, LINUX_X64));
        assertEquals("OpenJDK21U-jre_x64_linux_hotspot_21.0.13_9.tar.gz", artifact.fileName());
        assertEquals(HashAlgorithm.SHA256, artifact.checksum().orElseThrow().algorithm());
        assertEquals("a535a1df9c6284d9618872984e026ae0352d1f7e9e0605425c0e6114f47351dd", artifact.checksum().get().hex());
        assertEquals(52181297L, artifact.size().getAsLong());
        assertEquals(new ArtifactCoordinate("adoptium", "temurin", "21.0.13+9-LTS", "linux-x64-jre"), artifact.coordinate());
        assertEquals("jdk-21.0.13+9", artifact.metadata().get("release"));
        assertEquals("jre", artifact.metadata().get("image-type"));
        assertEquals(URI.create("https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/"
                + "OpenJDK21U-jre_x64_linux_hotspot_21.0.13_9.tar.gz"), artifact.url());
        assertTrue(artifact.javaMinimum().isEmpty());
    }

    @Test
    void windowsGetsTheZipNotTheMsi() throws Exception {
        ResolvedArtifact artifact = resolve(new FakeTransport().okResource(latest(25, "windows", "x64", "jre"),
                "adoptium-latest-25-windows-x64-jre.json"), AdoptiumProvider.request(25, WINDOWS_X64));
        assertEquals("OpenJDK25U-jre_x64_windows_hotspot_25.0.5_8.zip", artifact.fileName());
        assertEquals("windows-x64-jre", artifact.coordinate().build());
    }

    @Test
    void macAarch64AndMuslPlatforms() throws Exception {
        ResolvedArtifact mac = resolve(new FakeTransport().okResource(latest(25, "mac", "aarch64", "jre"),
                "adoptium-latest-25-mac-aarch64-jre.json"), AdoptiumProvider.request(25, MAC_AARCH64));
        assertEquals("OpenJDK25U-jre_aarch64_mac_hotspot_25.0.5_8.tar.gz", mac.fileName());
        ResolvedArtifact alpine = resolve(new FakeTransport().okResource(latest(25, "alpine-linux", "x64", "jre"),
                "adoptium-latest-25-alpine-linux-x64-jre.json"), AdoptiumProvider.request(25, ALPINE_X64));
        assertEquals("OpenJDK25U-jre_x64_alpine-linux_hotspot_25.0.5_8.tar.gz", alpine.fileName());
        assertEquals("alpine-linux-x64-jre", alpine.coordinate().build());
    }

    @Test
    void fallsBackToAJdkOnlyWhenThereIsNoJre() throws Exception {
        FakeTransport transport = new FakeTransport()
                .ok(latest(21, "linux", "x64", "jre"), "[]")
                .okResource(latest(21, "linux", "x64", "jdk"), "adoptium-latest-21-linux-x64-jre.json");
        // The fixture is a JRE listing, so the JDK query finds no matching image type: not available.
        ArtifactNotAvailableException e = assertThrows(ArtifactNotAvailableException.class,
                () -> resolve(transport, AdoptiumProvider.request(21, LINUX_X64)));
        assertTrue(e.getMessage().contains("jre or jdk 21 for linux/x64/glibc"), e.getMessage());
        assertEquals(2, transport.requests().size(), "asked for a JRE first, then for a JDK");

        String jdk = new String(FakeTransport.resource("adoptium-latest-21-linux-x64-jre.json"), StandardCharsets.UTF_8)
                .replace("\"image_type\": \"jre\"", "\"image_type\": \"jdk\"")
                .replace("OpenJDK21U-jre_", "OpenJDK21U-jdk_");
        ResolvedArtifact artifact = resolve(new FakeTransport()
                .ok(latest(21, "linux", "x64", "jre"), "[]")
                .ok(latest(21, "linux", "x64", "jdk"), jdk), AdoptiumProvider.request(21, LINUX_X64));
        assertEquals("linux-x64-jdk", artifact.coordinate().build());
        assertTrue(artifact.fileName().startsWith("OpenJDK21U-jdk_"));
    }

    @Test
    void anExplicitImageTypeIsNotReplaced() {
        FakeTransport transport = new FakeTransport().ok(latest(21, "linux", "x64", "jre"), "[]");
        assertThrows(ArtifactNotAvailableException.class, () -> resolve(transport,
                AdoptiumProvider.request(21, LINUX_X64).withOption(AdoptiumProvider.OPTION_IMAGE_TYPE, "jre")));
        assertEquals(1, transport.requests().size());
        assertThrows(IllegalArgumentException.class, () -> resolve(new FakeTransport(),
                AdoptiumProvider.request(21, LINUX_X64).withOption(AdoptiumProvider.OPTION_IMAGE_TYPE, "testimage")));
    }

    @Test
    void anExactReleaseUsesTheReleaseEndpoint() throws Exception {
        String uri = "https://api.adoptium.net/v3/assets/release_name/eclipse/jdk-21.0.13%2B9?architecture=x64"
                + "&heap_size=normal&image_type=jre&jvm_impl=hotspot&os=linux&project=jdk";
        ResolvedArtifact artifact = resolve(new FakeTransport().okResource(uri, "adoptium-release-jdk-21.0.13_9-linux-x64-jre.json"),
                AdoptiumProvider.request(21, LINUX_X64).withOption(AdoptiumProvider.OPTION_RELEASE, "jdk-21.0.13+9"));
        assertEquals("21.0.13+9-LTS", artifact.coordinate().version());
        assertEquals("a535a1df9c6284d9618872984e026ae0352d1f7e9e0605425c0e6114f47351dd", artifact.checksum().get().hex());
    }

    @Test
    void refusesBadRequestsBeforeAnyCall() {
        FakeTransport transport = new FakeTransport();
        for (String version : new String[] {null, "", "7", "021", "100", "21.0", "../21"}) {
            assertThrows(IllegalArgumentException.class, () -> resolve(transport,
                    ArtifactRequest.of("temurin").withVersion(version).withSystem(LINUX_X64)), String.valueOf(version));
        }
        assertThrows(IllegalArgumentException.class, () -> resolve(transport,
                AdoptiumProvider.request(21, LINUX_X64).withOption(AdoptiumProvider.OPTION_RELEASE, "../../x")));
        assertThrows(IllegalArgumentException.class, () -> resolve(transport,
                ArtifactRequest.of("zulu").withVersion("21").withSystem(LINUX_X64)));
        assertEquals(0, transport.requests().size());
    }

    @Test
    void unsupportedPlatformsGetAClearAnswer() {
        for (TargetPlatform platform : new TargetPlatform[] {
            new TargetPlatform(Os.OTHER, Arch.X64, Libc.NONE),
            new TargetPlatform(Os.LINUX, Arch.OTHER, Libc.GLIBC),
        }) {
            ArtifactNotAvailableException e = assertThrows(ArtifactNotAvailableException.class,
                    () -> resolve(new FakeTransport(), AdoptiumProvider.request(21, platform)));
            assertTrue(e.getMessage().contains("install Java yourself"), e.getMessage());
        }
    }

    @Test
    void aDownloadLinkOutsideTheAllowlistIsRefused() {
        String json = new String(FakeTransport.resource("adoptium-latest-21-linux-x64-jre.json"), StandardCharsets.UTF_8)
                .replace("\"link\": \"https://github.com/", "\"link\": \"https://evil.example.net/");
        assertThrows(HostNotAllowedException.class, () -> resolve(new FakeTransport()
                .ok(latest(21, "linux", "x64", "jre"), json), AdoptiumProvider.request(21, LINUX_X64)));
    }

    @Test
    void aMissingOrBrokenChecksumIsMalformed() {
        String fixture = new String(FakeTransport.resource("adoptium-latest-21-linux-x64-jre.json"), StandardCharsets.UTF_8);
        for (String broken : new String[] {
            fixture.replace("\"checksum\": \"a535a1df9c6284d9618872984e026ae0352d1f7e9e0605425c0e6114f47351dd\",", ""),
            fixture.replace("a535a1df9c6284d9618872984e026ae0352d1f7e9e0605425c0e6114f47351dd", "a535"),
        }) {
            assertThrows(MalformedResponseException.class, () -> resolve(new FakeTransport()
                    .ok(latest(21, "linux", "x64", "jre"), broken), AdoptiumProvider.request(21, LINUX_X64)));
        }
    }

    @Test
    void aFileNameWithAPathIsMalformed() {
        String json = new String(FakeTransport.resource("adoptium-latest-21-linux-x64-jre.json"), StandardCharsets.UTF_8)
                .replace("\"name\": \"OpenJDK21U-jre_x64_linux_hotspot_21.0.13_9.tar.gz\"",
                        "\"name\": \"../OpenJDK21U-jre_x64_linux_hotspot_21.0.13_9.tar.gz\"");
        assertThrows(MalformedResponseException.class, () -> resolve(new FakeTransport()
                .ok(latest(21, "linux", "x64", "jre"), json), AdoptiumProvider.request(21, LINUX_X64)));
    }

    @Test
    void theNewestReleaseWinsIfTheApiListsSeveral() throws Exception {
        String fixture = new String(FakeTransport.resource("adoptium-latest-21-linux-x64-jre.json"), StandardCharsets.UTF_8)
                .trim();
        String older = fixture.substring(1, fixture.length() - 1)
                .replace("\"security\": 13", "\"security\": 12")
                .replace("21.0.13+9-LTS", "21.0.12+7-LTS")
                .replace("OpenJDK21U-jre_x64_linux_hotspot_21.0.13_9", "OpenJDK21U-jre_x64_linux_hotspot_21.0.12_7");
        String both = "[" + older + "," + fixture.substring(1, fixture.length() - 1) + "]";
        ResolvedArtifact artifact = resolve(new FakeTransport().ok(latest(21, "linux", "x64", "jre"), both),
                AdoptiumProvider.request(21, LINUX_X64));
        assertEquals("21.0.13+9-LTS", artifact.coordinate().version());
    }

    @Test
    void isPartOfTheStandardRegistry() {
        ProviderRegistry registry = ProviderRegistry.standard(FakeTransport.client(new FakeTransport()));
        assertTrue(registry.allowedHosts("adoptium").permits(URI.create("https://github.com/adoptium/x")));
        assertTrue(registry.allowedHosts("adoptium").permits(URI.create("https://release-assets.githubusercontent.com/x")));
    }
}
