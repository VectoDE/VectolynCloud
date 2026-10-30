package net.vectolyn.cloud.download.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.List;
import net.vectolyn.cloud.download.ArtifactNotAvailableException;
import net.vectolyn.cloud.download.http.FakeTransport;
import org.junit.jupiter.api.Test;

class BibliothekProviderTest {

    private static final String GEYSER = "https://download.geysermc.org/v2/projects/geyser";
    private static final String PANDA = "https://downloads.hpfxd.com/v2/projects/pandaspigot";

    @Test
    void resolvesNewestGeyserForVelocity() throws Exception {
        FakeTransport transport = new FakeTransport()
                .okResource(GEYSER, "geyser-project.json")
                .okResource(GEYSER + "/versions/2.12.1", "geyser-version-2.12.1.json")
                .okResource(GEYSER + "/versions/2.12.1/builds/1276", "geyser-build-1276.json");
        ResolvedArtifact artifact = BibliothekProvider.geyser(FakeTransport.client(transport))
                .resolve(ArtifactRequest.of("geyser").withPlatform("velocity"));
        assertEquals(new ArtifactCoordinate("geysermc", "geyser", "2.12.1", "1276"), artifact.coordinate());
        assertEquals(URI.create(GEYSER + "/versions/2.12.1/builds/1276/downloads/velocity"), artifact.url());
        assertEquals("33971339325af4b1306cb0206e2470e20a3825004a0890734c46deaa963e7c37", artifact.checksum().orElseThrow().hex());
        assertEquals("Geyser-Velocity.jar", artifact.fileName());
    }

    @Test
    void geyserNeedsAPlatform() {
        assertThrows(IllegalArgumentException.class,
                () -> BibliothekProvider.geyser(FakeTransport.client(new FakeTransport())).resolve(ArtifactRequest.of("geyser")));
    }

    @Test
    void unknownDownloadKeyIsNotAvailable() {
        FakeTransport transport = new FakeTransport()
                .okResource(GEYSER + "/versions/2.12.1/builds/1276", "geyser-build-1276.json");
        assertThrows(ArtifactNotAvailableException.class, () -> BibliothekProvider.geyser(FakeTransport.client(transport))
                .resolve(ArtifactRequest.of("geyser").withVersion("2.12.1").withBuild("1276").withPlatform("sponge")));
    }

    @Test
    void pandaSpigotTakesTheHighestBuildOfAnUnsortedList() throws Exception {
        List<Long> builds = BibliothekProvider.parseVersionBuilds(FakeTransport.resource("pandaspigot-version-1.8.8.json"));
        assertEquals(128L, builds.get(0), "the API lists builds unsorted");
        FakeTransport transport = new FakeTransport()
                .okResource(PANDA, "pandaspigot-project.json")
                .okResource(PANDA + "/versions/1.8.8", "pandaspigot-version-1.8.8.json")
                .okResource(PANDA + "/versions/1.8.8/builds/226", "pandaspigot-build-226.json");
        ResolvedArtifact artifact = BibliothekProvider.pandaSpigot(FakeTransport.client(transport))
                .resolve(ArtifactRequest.of("pandaspigot"));
        assertEquals(new ArtifactCoordinate("pandaspigot", "pandaspigot", "1.8.8", "226"), artifact.coordinate());
        assertEquals(URI.create(PANDA + "/versions/1.8.8/builds/226/downloads/paperclip"), artifact.url());
        assertEquals("ac73146b275230cbd0612ef0f3d6b40333abd3182beae457cad5321efff6359b", artifact.checksum().orElseThrow().hex());
    }
}
