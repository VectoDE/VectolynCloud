package net.vectolyn.cloud.download.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import net.vectolyn.cloud.download.http.FakeTransport;
import net.vectolyn.cloud.download.provider.ArtifactRequest;
import net.vectolyn.cloud.download.provider.ProviderRegistry;
import org.junit.jupiter.api.Test;

class ArtifactCatalogTest {

    @Test
    void builtInCatalogLoadsAndUsesKnownProviders() {
        ArtifactCatalog catalog = ArtifactCatalog.builtIn();
        ProviderRegistry registry = ProviderRegistry.standard(FakeTransport.client(new FakeTransport()));
        for (CatalogEntry entry : catalog.entries()) {
            assertTrue(registry.find(entry.provider()).isPresent(), entry.id() + " uses unknown provider " + entry.provider());
            entry.alternativeOpt().ifPresent(alternative ->
                    assertTrue(registry.find(alternative.substring(0, alternative.indexOf(':'))).isPresent(), alternative));
        }
    }

    @Test
    void containsTheTransitionalPluginsWithChecksumSources() {
        Set<String> projects = ArtifactCatalog.builtIn().transitional().stream()
                .map(CatalogEntry::project).collect(Collectors.toSet());
        assertEquals(Set.of("luckperms", "tab-was-taken", "placeholderapi", "worldedit", "worldguard", "chunky", "spark",
                "proxyportal"), projects);
        for (CatalogEntry entry : ArtifactCatalog.builtIn().transitional()) {
            assertEquals("modrinth", entry.provider(), entry.id() + " should come from a source with checksums");
        }
    }

    @Test
    void containsTheStandardArtifacts() {
        ArtifactCatalog catalog = ArtifactCatalog.builtIn();
        for (String id : new String[] {"paper", "velocity", "fabric-server", "pandaspigot", "mariadb", "geyser-velocity",
            "floodgate-velocity", "floodgate-spigot", "floodgate-fabric", "viaversion", "viabackwards", "viarewind",
            "fabric-api", "fabricproxy-lite", "lithium", "ferritecore", "servercore"}) {
            assertTrue(catalog.get(id).isPresent(), id);
        }
    }

    @Test
    void requestsUseTheGameVersionAsConfigured() {
        ArtifactCatalog catalog = ArtifactCatalog.builtIn();
        ArtifactRequest paper = catalog.get("paper").orElseThrow().toRequest("26.3");
        assertEquals("26.3", paper.version());
        assertNull(paper.gameVersion());
        ArtifactRequest lithium = catalog.get("lithium").orElseThrow().toRequest("26.3");
        assertEquals("26.3", lithium.gameVersion());
        assertEquals("fabric", lithium.platform());
        assertNull(lithium.version());
        ArtifactRequest via = catalog.get("viaversion").orElseThrow().toRequest("26.3");
        assertNull(via.gameVersion());
        assertEquals("velocity", via.platform());
    }

    @Test
    void aPinnedVersionGoesIntoTheRequest() throws IOException {
        // The proxy software is pinned to the release line the Velocity bridge is compiled against.
        ArtifactRequest velocity = ArtifactCatalog.builtIn().get("velocity").orElseThrow().toRequest("26.3");
        assertEquals("4.3.1", velocity.version());
        ArtifactCatalog parsed = ArtifactCatalog.parse("schema-version: 1\nartifacts:\n  - id: a\n    kind: plugin\n"
                + "    type: standard\n    provider: modrinth\n    project: a\n    game-version: none\n    version: \"1.2\"\n");
        assertEquals("1.2", parsed.get("a").orElseThrow().versionOpt().orElseThrow());
        assertEquals("1.2", parsed.get("a").orElseThrow().toRequest(null).version());
        // Counter-check: without the pin, the newest release is asked for.
        assertNull(ArtifactCatalog.builtIn().get("viaversion").orElseThrow().toRequest("26.3").version());
    }

    @Test
    void aPinnedVersionOnlyFitsEntriesThatIgnoreTheGameVersion() {
        // A pin next to "game-version: version" would contradict the group's Minecraft version.
        IOException e = assertThrows(IOException.class, () -> ArtifactCatalog.parse("schema-version: 1\nartifacts:\n"
                + "  - id: a\n    kind: server\n    type: standard\n    provider: papermc\n    project: paper\n"
                + "    game-version: version\n    version: \"26.3\"\n"));
        assertTrue(e.getMessage().contains("version"), e.getMessage());
    }

    @Test
    void rejectsInvalidCatalogs() {
        assertThrows(IOException.class, () -> ArtifactCatalog.parse("schema-version: 1\nartifacts:\n  - id: a\n    kind: plugin\n"
                + "    type: transitional\n    provider: modrinth\n    project: a\n    game-version: none\n"));
        assertThrows(IOException.class, () -> ArtifactCatalog.parse("schema-version: 1\nartifacts:\n  - id: a\n    kind: plugin\n"
                + "    type: standard\n    provider: modrinth\n    project: a\n    game-version: none\n    extra: x\n"));
        assertThrows(IOException.class, () -> ArtifactCatalog.parse("schema-version: 2\nartifacts: []\n"));
    }
}
