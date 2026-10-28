package net.vectora.cloud.download.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.OptionalInt;
import net.vectora.cloud.download.ArtifactNotAvailableException;
import net.vectora.cloud.download.HostNotAllowedException;
import net.vectora.cloud.download.MalformedResponseException;
import net.vectora.cloud.download.hash.HashAlgorithm;
import net.vectora.cloud.download.http.FakeTransport;
import org.junit.jupiter.api.Test;

class PaperFillProviderTest {

    private static final String FILL = "https://fill.papermc.io/v3/projects/";

    @Test
    void resolvesNewestStablePaperBuildWithJavaMinimum() throws Exception {
        FakeTransport transport = new FakeTransport()
                .okResource(FILL + "paper/versions/26.3/builds?channel=STABLE", "fill-paper-builds-26.3.json")
                .okResource(FILL + "paper/versions/26.3", "fill-paper-version-26.3.json");
        ResolvedArtifact artifact = new PaperFillProvider(FakeTransport.client(transport))
                .resolve(ArtifactRequest.of("paper").withVersion("26.3"));

        assertEquals(new ArtifactCoordinate("papermc", "paper", "26.3", "227"), artifact.coordinate());
        assertEquals(URI.create("https://fill-data.papermc.io/v1/objects/"
                + "416b7d105b1d0192df4afa4b29d2800d9dbeda092a82fbc5b511248a54adde15/paper-26.3-227.jar"), artifact.url());
        assertEquals(HashAlgorithm.SHA256, artifact.checksum().orElseThrow().algorithm());
        assertEquals("416b7d105b1d0192df4afa4b29d2800d9dbeda092a82fbc5b511248a54adde15", artifact.checksum().get().hex());
        assertEquals(OptionalInt.of(25), artifact.javaMinimum());
        assertEquals(55257780L, artifact.size().getAsLong());
        assertEquals("paper-26.3-227.jar", artifact.fileName());
    }

    @Test
    void resolvesASpecificBuild() throws Exception {
        FakeTransport transport = new FakeTransport()
                .okResource(FILL + "paper/versions/26.3/builds/227", "fill-paper-build-227.json")
                .okResource(FILL + "paper/versions/26.3", "fill-paper-version-26.3.json");
        ResolvedArtifact artifact = new PaperFillProvider(FakeTransport.client(transport))
                .resolve(ArtifactRequest.of("paper").withVersion("26.3").withBuild("227"));
        assertEquals("227", artifact.coordinate().build());
    }

    @Test
    void velocityWithoutVersionSkipsVersionsWithoutStableBuild() throws Exception {
        FakeTransport transport = new FakeTransport()
                .okResource(FILL + "velocity", "fill-velocity-project.json")
                .okResource(FILL + "velocity/versions/4.3.2-SNAPSHOT/builds?channel=STABLE",
                        "fill-velocity-builds-4.3.2-SNAPSHOT-stable.json")
                .okResource(FILL + "velocity/versions/4.3.1/builds?channel=STABLE", "fill-velocity-builds-4.3.1-stable.json")
                .okResource(FILL + "velocity/versions/4.3.1", "fill-velocity-version-4.3.1.json");
        ResolvedArtifact artifact = new PaperFillProvider(FakeTransport.client(transport)).resolve(ArtifactRequest.of("velocity"));
        assertEquals(new ArtifactCoordinate("papermc", "velocity", "4.3.1", "45"), artifact.coordinate());
        assertEquals("0844bcabdcdb4f5b875906fd4c3365bc1bb76918e42bc595ed88ad195258dd75", artifact.checksum().orElseThrow().hex());
        assertEquals(OptionalInt.of(25), artifact.javaMinimum());
    }

    @Test
    void unknownBuildIsNotAvailable() {
        FakeTransport transport = new FakeTransport().status(FILL + "paper/versions/26.3/builds/9999", 404);
        assertThrows(ArtifactNotAvailableException.class, () -> new PaperFillProvider(FakeTransport.client(transport))
                .resolve(ArtifactRequest.of("paper").withVersion("26.3").withBuild("9999")));
    }

    @Test
    void refusesDownloadUrlOnAForeignHost() {
        String build = new String(FakeTransport.resource("fill-paper-build-227.json"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("https://fill-data.papermc.io/", "https://evil.example.net/");
        FakeTransport transport = new FakeTransport()
                .ok(FILL + "paper/versions/26.3/builds/227", build)
                .okResource(FILL + "paper/versions/26.3", "fill-paper-version-26.3.json");
        assertThrows(HostNotAllowedException.class, () -> new PaperFillProvider(FakeTransport.client(transport))
                .resolve(ArtifactRequest.of("paper").withVersion("26.3").withBuild("227")));
    }

    @Test
    void rejectsPathInjectionInTheRequest() {
        FakeTransport transport = new FakeTransport();
        assertThrows(IllegalArgumentException.class, () -> new PaperFillProvider(FakeTransport.client(transport))
                .resolve(ArtifactRequest.of("paper").withVersion("../../admin")));
    }

    @Test
    void reportsBrokenJson() {
        assertThrows(MalformedResponseException.class, () -> PaperFillProvider.parseBuilds("{\"not\":\"a list\"}".getBytes()));
        assertThrows(MalformedResponseException.class, () -> PaperFillProvider.parseVersion("[".getBytes()));
        assertThrows(MalformedResponseException.class, () -> PaperFillProvider.parseBuild(
                "{\"id\":1,\"channel\":\"STABLE\",\"downloads\":{\"server:default\":{\"name\":\"a\",\"checksums\":{\"sha256\":\"xyz\"},\"url\":\"https://fill-data.papermc.io/a\"}}}"
                        .getBytes()));
    }
}
