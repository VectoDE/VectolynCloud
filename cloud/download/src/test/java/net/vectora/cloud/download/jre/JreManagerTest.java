package net.vectora.cloud.download.jre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import net.vectora.cloud.download.ChecksumMismatchException;
import net.vectora.cloud.download.cache.ArtifactCache;
import net.vectora.cloud.download.cache.ArtifactDownloader;
import net.vectora.cloud.download.http.FakeTransport;
import net.vectora.cloud.download.lock.LockedArtifact;
import net.vectora.cloud.download.lock.SoftwareLock;
import net.vectora.cloud.download.lock.SoftwareLockStore;
import net.vectora.cloud.download.lock.Trust;
import net.vectora.cloud.download.provider.AdoptiumProvider;
import net.vectora.cloud.download.provider.ProviderRegistry;
import net.vectora.cloud.download.provider.TargetPlatform;
import net.vectora.cloud.download.provider.TargetPlatform.Arch;
import net.vectora.cloud.download.provider.TargetPlatform.Libc;
import net.vectora.cloud.download.provider.TargetPlatform.Os;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JreManagerTest {

    private static final TargetPlatform LINUX_X64 = new TargetPlatform(Os.LINUX, Arch.X64, Libc.GLIBC);
    private static final String FIXTURE_SHA = "a535a1df9c6284d9618872984e026ae0352d1f7e9e0605425c0e6114f47351dd";
    private static final String LINK = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/"
            + "OpenJDK21U-jre_x64_linux_hotspot_21.0.13_9.tar.gz";

    @TempDir
    Path home;

    private static String latest(int feature) {
        return "https://api.adoptium.net/v3/assets/latest/" + feature + "/hotspot?architecture=x64&heap_size=normal"
                + "&image_type=jre&jvm_impl=hotspot&os=linux&project=jdk&vendor=eclipse";
    }

    /** The stored API answer, pointing at a test archive. */
    private static String listing(byte[] archive) {
        return new String(FakeTransport.resource("adoptium-latest-21-linux-x64-jre.json"), StandardCharsets.UTF_8)
                .replace(FIXTURE_SHA, JreArchives.sha256(archive).hex())
                .replace("\"size\": 52181297", "\"size\": " + archive.length);
    }

    private JreManager manager(FakeTransport transport, SystemJavaFinder system) {
        var client = FakeTransport.client(transport);
        AdoptiumProvider adoptium = new AdoptiumProvider(client);
        ArtifactDownloader downloader = new ArtifactDownloader(client, ProviderRegistry.of(List.of(adoptium)),
                new ArtifactCache(home.resolve("storage")));
        return new JreManager(home, adoptium, downloader, SoftwareLockStore.inHome(home), system, LINUX_X64);
    }

    private SoftwareLock lock() throws Exception {
        return SoftwareLockStore.inHome(home).read();
    }

    @Test
    void downloadsInstallsAndPinsAJre() throws Exception {
        byte[] tar = JreArchives.linuxTarGz("21.0.13", 0755);
        FakeTransport transport = new FakeTransport().ok(latest(21), listing(tar)).ok(LINK, tar);
        JreManager manager = manager(transport, null);

        JavaRuntime runtime = manager.provide(JavaRequirement.forGroup("pandaspigot", OptionalInt.of(8), 0));

        assertEquals(21, runtime.feature());
        assertEquals(JavaRuntime.Origin.MANAGED, runtime.origin());
        assertTrue(runtime.executable().startsWith(home.resolve("storage/jre/temurin-21-linux-x64")), runtime.executable().toString());
        assertTrue(Files.isRegularFile(runtime.executable()));
        LockedArtifact entry = lock().get("jre-temurin-21-linux-x64").orElseThrow();
        assertEquals(Trust.SOURCE, entry.trust());
        assertEquals(JreArchives.sha256(tar), entry.sha256());
        assertEquals("adoptium:temurin:21.0.13+9-LTS#linux-x64-jre", entry.coordinate().toString());
        assertEquals("OpenJDK21U-jre_x64_linux_hotspot_21.0.13_9.tar.gz", entry.fileName());

        int requests = transport.requests().size();
        assertEquals(runtime, manager.provide(21));
        assertEquals(requests, transport.requests().size(), "an installed, pinned runtime needs no network");
    }

    @Test
    void usesAFittingSystemJavaFirst() throws Exception {
        Path system = Files.createDirectories(home.resolve("system-java/bin")).getParent();
        Files.writeString(system.resolve("bin/java"), "");
        Files.writeString(system.resolve("release"), JreArchives.release("25.0.5"));
        FakeTransport transport = new FakeTransport();
        JreManager manager = manager(transport, new SystemJavaFinder(Map.of("JAVA_HOME", system.toString()), LINUX_X64,
                executable -> java.util.Optional.empty()));

        JavaRuntime runtime = manager.provide(JavaRequirement.forGroup("paper", OptionalInt.of(25), 0));
        assertEquals(JavaRuntime.Origin.SYSTEM, runtime.origin());
        assertEquals(system.toRealPath(), runtime.home());
        assertEquals(List.of(), transport.requests());
        assertTrue(lock().entries().isEmpty(), "a system Java is not pinned");
    }

    @Test
    void aSystemJavaWithAnotherVersionLeadsToADownload() throws Exception {
        Path system = Files.createDirectories(home.resolve("system-java/bin")).getParent();
        Files.writeString(system.resolve("bin/java"), "");
        Files.writeString(system.resolve("release"), JreArchives.release("25.0.5"));
        byte[] tar = JreArchives.linuxTarGz("21.0.13", 0755);
        JreManager manager = manager(new FakeTransport().ok(latest(21), listing(tar)).ok(LINK, tar),
                new SystemJavaFinder(Map.of("JAVA_HOME", system.toString()), LINUX_X64, executable -> java.util.Optional.empty()));

        JavaRuntime runtime = manager.provide(JavaRequirement.forGroup("pandaspigot", OptionalInt.empty(), 0));
        assertEquals(JavaRuntime.Origin.MANAGED, runtime.origin());
        assertEquals(21, runtime.feature());
    }

    @Test
    void aWrongChecksumInstallsNothingAndPinsNothing() throws Exception {
        byte[] tar = JreArchives.linuxTarGz("21.0.13", 0755);
        byte[] tampered = tar.clone();
        tampered[tampered.length / 2] ^= 1;
        JreManager manager = manager(new FakeTransport().ok(latest(21), listing(tar)).ok(LINK, tampered), null);

        assertThrows(ChecksumMismatchException.class, () -> manager.provide(21));
        assertFalse(Files.exists(home.resolve("storage/jre/temurin-21-linux-x64")));
        assertTrue(lock().entries().isEmpty());
    }

    @Test
    void reinstallsFromThePinWithoutAskingTheApi() throws Exception {
        byte[] tar = JreArchives.linuxTarGz("21.0.13", 0755);
        manager(new FakeTransport().ok(latest(21), listing(tar)).ok(LINK, tar), null).provide(21);
        JreInstaller.deleteTree(home.resolve("storage/jre"));
        JreInstaller.deleteTree(home.resolve("storage/artifacts"));

        FakeTransport second = new FakeTransport().ok(LINK, tar);
        JavaRuntime runtime = manager(second, null).provide(21);
        assertTrue(Files.isRegularFile(runtime.executable()));
        assertEquals(List.of(java.net.URI.create(LINK)), second.requests(), "only the pinned URL, no API call");
    }

    @Test
    void anUpdateInstallsTheNewReleaseAndKeepsThePreviousPin() throws Exception {
        byte[] first = JreArchives.linuxTarGz("21.0.13", 0755);
        manager(new FakeTransport().ok(latest(21), listing(first)).ok(LINK, first), null).provide(21);

        byte[] second = JreArchives.linuxTarGz("21.0.14", 0755);
        String newerListing = listing(second).replace("21.0.13", "21.0.14");
        String newerLink = LINK.replace("21.0.13", "21.0.14");
        JavaRuntime runtime = manager(new FakeTransport().ok(latest(21), newerListing).ok(newerLink, second), null)
                .update(21);

        assertEquals("21.0.14", runtime.version());
        LockedArtifact entry = lock().get("jre-temurin-21-linux-x64").orElseThrow();
        assertEquals("21.0.14+9-LTS", entry.coordinate().version());
        assertEquals("21.0.13+9-LTS", entry.previousOpt().orElseThrow().coordinate().version());

        FakeTransport unchanged = new FakeTransport().ok(latest(21), newerListing);
        assertEquals(runtime, manager(unchanged, null).update(21));
        assertEquals(1, unchanged.requests().size(), "same release: only the API is asked");
    }

    @Test
    void anOverrideBelowTheMinimumFailsBeforeAnyDownload() {
        FakeTransport transport = new FakeTransport();
        assertThrows(JreException.class,
                () -> manager(transport, null).provide(JavaRequirement.forGroup("paper", OptionalInt.of(25), 21)));
        assertEquals(List.of(), transport.requests());
    }

    @Test
    void lockNames() {
        assertEquals("jre-temurin-25-windows-x64",
                JreManager.lockName(25, new TargetPlatform(Os.WINDOWS, Arch.X64, Libc.NONE)));
        assertEquals("jre-temurin-21-linux-x64", JreManager.lockName(21, LINUX_X64));
    }
}
