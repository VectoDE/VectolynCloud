package net.vectora.cloud.download;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.vectora.cloud.download.cache.ArtifactCache;
import net.vectora.cloud.download.cache.ArtifactDownloader;
import net.vectora.cloud.download.cache.DownloadedArtifact;
import net.vectora.cloud.download.hash.HashAlgorithm;
import net.vectora.cloud.download.hash.MultiDigest;
import net.vectora.cloud.download.http.DownloadClient;
import net.vectora.cloud.download.lock.LockedArtifact;
import net.vectora.cloud.download.lock.SoftwareLock;
import net.vectora.cloud.download.lock.SoftwareLockFile;
import net.vectora.cloud.download.lock.Trust;
import net.vectora.cloud.download.provider.ArtifactRequest;
import net.vectora.cloud.download.provider.MojangProvider;
import net.vectora.cloud.download.provider.ProviderRegistry;
import net.vectora.cloud.download.provider.ResolvedArtifact;
import net.vectora.cloud.download.provider.TargetPlatform;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Live smoke test against the real download APIs. Tagged {@code network}, so it never runs in
 * the normal build. Run it with {@code ./gradlew :VectoraCloud:cloud-download:networkTest}.
 * A summary goes to {@code build/network-smoke.txt}.
 */
@Tag("network")
class LiveSmokeTest {

    private static final List<String> REPORT = new ArrayList<>();

    private final DownloadClient client = DownloadClient.createDefault();
    private final ProviderRegistry registry = ProviderRegistry.standard(client);

    @TempDir
    Path home;

    /**
     * Opens the download URL through the real client (allowlist and redirects included), reads
     * the first kilobyte and drops the connection. Proves the redirect chain stays on allowed hosts.
     */
    private void probe(ResolvedArtifact artifact) throws Exception {
        int read = client.get(artifact.url(), registry.allowedHosts(artifact.coordinate().provider()), Long.MAX_VALUE,
                body -> body.read(new byte[1024]));
        REPORT.add(String.format("%22s download reachable, first read returned %d bytes", "", read));
    }

    private ResolvedArtifact resolve(String label, String provider, ArtifactRequest request) throws Exception {
        ResolvedArtifact artifact = registry.get(provider).resolve(request);
        probe(artifact);
        assertEquals("https", artifact.url().getScheme());
        assertTrue(registry.allowedHosts(provider).permits(artifact.url()), artifact.url().toString());
        REPORT.add(String.format("%-22s %-48s %s%n%22s %s%n%22s %s", label, artifact.coordinate(),
                artifact.checksum().map(Object::toString).orElse("no checksum (TOFU)"),
                "", artifact.url(), "", artifact.javaMinimum().isPresent()
                        ? "java minimum " + artifact.javaMinimum().getAsInt() : ""));
        return artifact;
    }

    @Test
    void resolvesTheServerSoftware() throws Exception {
        ResolvedArtifact paper = resolve("Paper 26.3", "papermc", ArtifactRequest.of("paper").withVersion("26.3"));
        assertEquals(HashAlgorithm.SHA256, paper.checksum().orElseThrow().algorithm());
        assertTrue(paper.javaMinimum().isPresent());
        ResolvedArtifact velocity = resolve("Velocity", "papermc", ArtifactRequest.of("velocity"));
        assertTrue(velocity.checksum().isPresent());
        ResolvedArtifact fabric = resolve("Fabric launcher 26.3", "fabric",
                ArtifactRequest.of("fabric-server").withGameVersion("26.3"));
        assertTrue(fabric.trustOnFirstUse());
        ResolvedArtifact panda = resolve("PandaSpigot", "pandaspigot", ArtifactRequest.of("pandaspigot"));
        assertEquals(HashAlgorithm.SHA256, panda.checksum().orElseThrow().algorithm());
    }

    @Test
    void resolvesPluginsAndRuntimeFacts() throws Exception {
        resolve("Geyser-Velocity", "geysermc", ArtifactRequest.of("geyser").withPlatform("velocity"));
        resolve("Floodgate-Velocity", "geysermc", ArtifactRequest.of("floodgate").withPlatform("velocity"));
        ResolvedArtifact via = resolve("ViaVersion (Modrinth)", "modrinth", ArtifactRequest.of("viaversion").withPlatform("velocity"));
        assertEquals(HashAlgorithm.SHA512, via.checksum().orElseThrow().algorithm());
        resolve("ViaVersion (Hangar)", "hangar", ArtifactRequest.of("ViaVersion").withPlatform("PAPER").withGameVersion("26.3"));
        resolve("Lithium 26.3", "modrinth", ArtifactRequest.of("lithium").withPlatform("fabric").withGameVersion("26.3"));
        resolve("MariaDB 11.8 Windows", "mariadb", ArtifactRequest.of("mariadb").withVersion("11.8")
                .withSystem(new TargetPlatform(TargetPlatform.Os.WINDOWS, TargetPlatform.Arch.X64, TargetPlatform.Libc.NONE)));
        resolve("MariaDB 11.8 Linux", "mariadb", ArtifactRequest.of("mariadb").withVersion("11.8")
                .withSystem(new TargetPlatform(TargetPlatform.Os.LINUX, TargetPlatform.Arch.X64, TargetPlatform.Libc.GLIBC)));
        int java = ((MojangProvider) registry.get("mojang")).javaMajorVersion("26.3");
        REPORT.add("Mojang 26.3             javaVersion.majorVersion = " + java);
        assertTrue(java >= 21);
    }

    @Test
    void downloadsAndVerifiesSmallArtifacts() throws Exception {
        ArtifactDownloader downloader = new ArtifactDownloader(client, registry, new ArtifactCache(home.resolve("cache")));
        SoftwareLock lock = SoftwareLock.empty();

        ResolvedArtifact chunky = registry.get("modrinth").resolve(
                ArtifactRequest.of("chunky").withPlatform("fabric").withGameVersion("26.3"));
        DownloadedArtifact chunkyFile = downloader.download(chunky, null);
        byte[] bytes = Files.readAllBytes(chunkyFile.file());
        assertArrayEquals(chunky.checksum().orElseThrow().hex().getBytes(StandardCharsets.US_ASCII),
                MultiDigest.of(bytes, Set.of(HashAlgorithm.SHA512)).get(HashAlgorithm.SHA512).hex()
                        .getBytes(StandardCharsets.US_ASCII));
        assertEquals(chunkyFile.sha256().hex(), chunkyFile.file().getFileName().toString());
        lock = lock.put("chunky", chunkyFile.toLocked(chunky));
        REPORT.add("Downloaded " + chunky.fileName() + " (" + chunkyFile.size() + " bytes), " + chunky.checksum().get()
                .algorithm().id() + " verified, cache " + chunkyFile.sha256().hex());

        ResolvedArtifact fabric = registry.get("fabric").resolve(ArtifactRequest.of("fabric-server").withGameVersion("26.3"));
        DownloadedArtifact fabricFile = downloader.download(fabric, null);
        assertEquals(Trust.TOFU, fabricFile.trust());
        lock = lock.put("fabric-server", fabricFile.toLocked(fabric));
        REPORT.add("Downloaded " + fabric.fileName() + " (" + fabricFile.size() + " bytes), TOFU pinned sha256 "
                + fabricFile.sha256().hex());

        // Second round: cache hit for the checksummed one, TOFU check for the launcher.
        DownloadedArtifact again = downloader.download(chunky, lock.get("chunky").orElseThrow());
        assertTrue(again.fromCache());
        Files.delete(fabricFile.file());
        LockedArtifact pinned = lock.get("fabric-server").orElseThrow();
        DownloadedArtifact tofuAgain = downloader.download(fabric, pinned);
        assertEquals(pinned.sha256(), tofuAgain.sha256());
        REPORT.add("Second download of the Fabric launcher matches the TOFU pin");

        Path lockFile = home.resolve(SoftwareLockFile.FILE_NAME);
        SoftwareLockFile.write(lockFile, lock);
        assertEquals(lock, SoftwareLockFile.read(lockFile));
        REPORT.add("software.lock.yml:\n" + Files.readString(lockFile));
        assertFalse(lock.entries().isEmpty());
    }

    @AfterAll
    static void writeReport() throws IOException {
        Path out = Path.of("build", "network-smoke.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, REPORT, StandardCharsets.UTF_8);
        REPORT.forEach(System.out::println);
    }
}
