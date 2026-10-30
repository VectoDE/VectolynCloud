package net.vectolyn.cloud.download.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import net.vectolyn.cloud.download.ArtifactNotAvailableException;
import net.vectolyn.cloud.download.hash.HashAlgorithm;
import net.vectolyn.cloud.download.http.FakeTransport;
import org.junit.jupiter.api.Test;

class ModrinthProviderTest {

    private static final String QUERY = "https://api.modrinth.com/v2/project/viaversion/version"
            + "?loaders=%5B%22velocity%22%5D&game_versions=%5B%2226.3%22%5D&include_changelog=false";

    private static ArtifactRequest request() {
        return ArtifactRequest.of("viaversion").withPlatform("velocity").withGameVersion("26.3");
    }

    @Test
    void picksNewestReleaseAndItsSha512() throws Exception {
        FakeTransport transport = new FakeTransport().okResource(QUERY, "modrinth-viaversion-velocity-26.3.json");
        ResolvedArtifact artifact = new ModrinthProvider(FakeTransport.client(transport)).resolve(request());
        assertEquals("5.12.2", artifact.coordinate().version());
        assertEquals(HashAlgorithm.SHA512, artifact.checksum().orElseThrow().algorithm(), "strongest hash wins over sha1");
        assertEquals("c6af11f50e14bc1ba239f5a90be653cce85feee30c473a4b53336b4d5527d790369cf43843f7cb06d3ac3a34fc207e7203436737ca719070644101472453401b",
                artifact.checksum().get().hex());
        assertEquals("cdn.modrinth.com", artifact.url().getHost());
        assertEquals("ViaVersion-5.12.2.jar", artifact.fileName());
    }

    @Test
    void skipsNewerBetaUnlessAsked() throws Exception {
        String json = new String(FakeTransport.resource("modrinth-viaversion-velocity-26.3.json"), StandardCharsets.UTF_8)
                .replace("2026-10-27T14:59:20.494861Z", "2026-10-28T00:00:00Z");
        FakeTransport transport = new FakeTransport().ok(QUERY, json);
        ModrinthProvider provider = new ModrinthProvider(FakeTransport.client(transport));
        assertEquals("5.12.2", provider.resolve(request()).coordinate().version());
        assertEquals("5.12.2-SNAPSHOT+1110", provider.resolve(request().withChannel("beta")).coordinate().version());
    }

    @Test
    void exactVersionAndMissingVersion() throws Exception {
        FakeTransport transport = new FakeTransport().okResource(QUERY, "modrinth-viaversion-velocity-26.3.json");
        ModrinthProvider provider = new ModrinthProvider(FakeTransport.client(transport));
        assertEquals("5.12.2-SNAPSHOT+1109",
                provider.resolve(request().withVersion("5.12.2-SNAPSHOT+1109")).coordinate().version());
        assertThrows(ArtifactNotAvailableException.class, () -> provider.resolve(request().withVersion("1.0.0")));
    }

    @Test
    void emptyResultIsNotAvailable() {
        FakeTransport transport = new FakeTransport().ok(QUERY, "[]");
        assertThrows(ArtifactNotAvailableException.class,
                () -> new ModrinthProvider(FakeTransport.client(transport)).resolve(request()));
    }

    @Test
    void needsALoader() {
        assertThrows(IllegalArgumentException.class, () -> new ModrinthProvider(FakeTransport.client(new FakeTransport()))
                .resolve(ArtifactRequest.of("viaversion")));
    }
}
