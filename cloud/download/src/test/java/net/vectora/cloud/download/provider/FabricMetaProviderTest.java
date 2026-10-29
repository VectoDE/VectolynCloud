package net.vectora.cloud.download.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import net.vectora.cloud.download.ArtifactNotAvailableException;
import net.vectora.cloud.download.http.FakeTransport;
import org.junit.jupiter.api.Test;

class FabricMetaProviderTest {

    private static final String META = "https://meta.fabricmc.net/v2/versions/";

    @Test
    void resolvesLauncherWithNewestStableLoaderAndInstallerWithoutChecksum() throws Exception {
        FakeTransport transport = new FakeTransport()
                .okResource(META + "loader", "fabric-loader.json")
                .okResource(META + "installer", "fabric-installer.json")
                .okResource(META + "loader/26.3/0.20.1", "fabric-loader-26.3-0.20.1.json");
        ResolvedArtifact artifact = new FabricMetaProvider(FakeTransport.client(transport))
                .resolve(ArtifactRequest.of("fabric-server").withGameVersion("26.3"));
        assertEquals(URI.create(META + "loader/26.3/0.20.1/1.1.2/server/jar"), artifact.url());
        assertTrue(artifact.trustOnFirstUse(), "Fabric publishes no checksum for the launcher");
        assertEquals(new ArtifactCoordinate("fabric", "fabric-server", "26.3", "loader-0.20.1+installer-1.1.2"),
                artifact.coordinate());
        assertEquals("fabric-server-mc.26.3-loader.0.20.1-launcher.1.1.2.jar", artifact.fileName());
    }

    @Test
    void incompatibleLoaderIsNotAvailable() {
        FakeTransport transport = new FakeTransport().status(META + "loader/99.9/0.20.1", 400);
        assertThrows(ArtifactNotAvailableException.class, () -> new FabricMetaProvider(FakeTransport.client(transport))
                .resolve(ArtifactRequest.of("fabric-server").withGameVersion("99.9")
                        .withOption(FabricMetaProvider.OPTION_LOADER, "0.20.1")
                        .withOption(FabricMetaProvider.OPTION_INSTALLER, "1.1.2")));
    }
}
