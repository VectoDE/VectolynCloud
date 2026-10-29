package net.vectora.cloud.download.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.vectora.cloud.download.ArtifactNotAvailableException;
import net.vectora.cloud.download.hash.HashAlgorithm;
import net.vectora.cloud.download.http.FakeTransport;
import org.junit.jupiter.api.Test;

class HangarProviderTest {

    private static final String QUERY = "https://hangar.papermc.io/api/v1/projects/ViaVersion/versions"
            + "?limit=1&offset=0&channel=Release&platform=PAPER&platformVersion=26.3";

    @Test
    void resolvesNewestReleaseForPaper() throws Exception {
        FakeTransport transport = new FakeTransport().okResource(QUERY, "hangar-viaversion-paper-26.3.json");
        ResolvedArtifact artifact = new HangarProvider(FakeTransport.client(transport))
                .resolve(ArtifactRequest.of("ViaVersion").withPlatform("paper").withGameVersion("26.3"));
        assertEquals("5.12.2", artifact.coordinate().version());
        assertEquals(HashAlgorithm.SHA256, artifact.checksum().orElseThrow().algorithm());
        assertEquals("c0b63badcb0f7d38f4443e06820f2b8142dd4f0d3b0511f9330ff1050430e6b2", artifact.checksum().get().hex());
        assertEquals("hangarcdn.papermc.io", artifact.url().getHost());
    }

    @Test
    void refusesExternalDownloadsWithoutChecksum() {
        String json = "{\"pagination\":{\"count\":1},\"result\":[{\"name\":\"1.0\",\"downloads\":{\"PAPER\":"
                + "{\"fileInfo\":null,\"externalUrl\":\"https://example.org/x.jar\",\"downloadUrl\":null}}}]}";
        FakeTransport transport = new FakeTransport().ok(QUERY, json);
        assertThrows(ArtifactNotAvailableException.class, () -> new HangarProvider(FakeTransport.client(transport))
                .resolve(ArtifactRequest.of("ViaVersion").withPlatform("PAPER").withGameVersion("26.3")));
        String withInfo = "{\"pagination\":{\"count\":1},\"result\":[{\"name\":\"1.0\",\"downloads\":{\"PAPER\":"
                + "{\"fileInfo\":{\"name\":\"x.jar\",\"sizeBytes\":1,\"sha256Hash\":null},"
                + "\"externalUrl\":\"https://example.org/x.jar\",\"downloadUrl\":null}}}]}";
        FakeTransport second = new FakeTransport().ok(QUERY, withInfo);
        assertThrows(ArtifactNotAvailableException.class, () -> new HangarProvider(FakeTransport.client(second))
                .resolve(ArtifactRequest.of("ViaVersion").withPlatform("PAPER").withGameVersion("26.3")));
    }
}
