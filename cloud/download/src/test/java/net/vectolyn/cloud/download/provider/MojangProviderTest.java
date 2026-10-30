package net.vectolyn.cloud.download.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;
import net.vectolyn.cloud.download.ArtifactNotAvailableException;
import net.vectolyn.cloud.download.ChecksumMismatchException;
import net.vectolyn.cloud.download.hash.HashAlgorithm;
import net.vectolyn.cloud.download.http.FakeTransport;
import org.junit.jupiter.api.Test;

class MojangProviderTest {

    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String VERSION_26_3 =
            "https://piston-meta.mojang.com/v1/packages/5df1795e16eea19d75b923d66ecca9ce183353c5/26.3.json";

    private static FakeTransport transport() {
        return new FakeTransport()
                .okResource(MANIFEST, "mojang-manifest.json")
                .okResource(VERSION_26_3, "mojang-version-26.3.json");
    }

    @Test
    void readsJavaVersionAndServerJar() throws Exception {
        MojangProvider provider = new MojangProvider(FakeTransport.client(transport()));
        ResolvedArtifact artifact = provider.resolve(ArtifactRequest.of("server").withVersion("26.3"));
        assertEquals(OptionalInt.of(25), artifact.javaMinimum());
        assertEquals(HashAlgorithm.SHA1, artifact.checksum().orElseThrow().algorithm());
        assertEquals("33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c", artifact.checksum().get().hex());
        assertEquals("piston-data.mojang.com", artifact.url().getHost());
    }

    @Test
    void latestReleaseWhenNoVersionGiven() throws Exception {
        assertEquals(25, new MojangProvider(FakeTransport.client(transport())).javaMajorVersion("26.3"));
        ResolvedArtifact artifact = new MojangProvider(FakeTransport.client(transport())).resolve(ArtifactRequest.of("server"));
        assertEquals("26.3", artifact.coordinate().version());
    }

    @Test
    void versionJsonMustMatchTheManifestSha1() {
        String tampered = new String(FakeTransport.resource("mojang-version-26.3.json"), StandardCharsets.UTF_8)
                .replace("\"majorVersion\":25", "\"majorVersion\":8");
        FakeTransport transport = new FakeTransport().okResource(MANIFEST, "mojang-manifest.json").ok(VERSION_26_3, tampered);
        assertThrows(ChecksumMismatchException.class,
                () -> new MojangProvider(FakeTransport.client(transport)).javaMajorVersion("26.3"));
    }

    @Test
    void unknownVersionIsNotAvailable() {
        assertThrows(ArtifactNotAvailableException.class,
                () -> new MojangProvider(FakeTransport.client(transport())).javaMajorVersion("0.0.1"));
    }
}
