package net.vectora.cloud.download.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import net.vectora.cloud.download.ArtifactNotAvailableException;
import net.vectora.cloud.download.hash.HashAlgorithm;
import net.vectora.cloud.download.http.FakeTransport;
import net.vectora.cloud.download.provider.TargetPlatform.Arch;
import net.vectora.cloud.download.provider.TargetPlatform.Libc;
import net.vectora.cloud.download.provider.TargetPlatform.Os;
import org.junit.jupiter.api.Test;

class MariaDbProviderTest {

    private static final String API = "https://downloads.mariadb.org/rest-api/mariadb/";
    private static final TargetPlatform WINDOWS = new TargetPlatform(Os.WINDOWS, Arch.X64, Libc.NONE);
    private static final TargetPlatform LINUX = new TargetPlatform(Os.LINUX, Arch.X64, Libc.GLIBC);

    private static FakeTransport transport() {
        return new FakeTransport()
                .okResource(API, "mariadb-series.json")
                .okResource(API + "11.8/latest/", "mariadb-11.8-latest.json")
                .okResource(API + "11.8.9/", "mariadb-11.8.9.json");
    }

    @Test
    void windowsZipOverHttpsFromTheArchiveMirror() throws Exception {
        ResolvedArtifact artifact = new MariaDbProvider(FakeTransport.client(transport()))
                .resolve(ArtifactRequest.of("mariadb").withVersion("11.8").withSystem(WINDOWS));
        assertEquals("mariadb-11.8.9-winx64.zip", artifact.fileName());
        assertEquals(URI.create("https://downloads.mariadb.org/rest-api/mariadb/11.8.9/mariadb-11.8.9-winx64.zip?mirror=archive"),
                artifact.url());
        assertEquals(HashAlgorithm.SHA512, artifact.checksum().orElseThrow().algorithm(), "strongest of sha1/sha256/sha512");
        assertEquals("11.8.9", artifact.coordinate().version());
    }

    @Test
    void linuxGlibcTarGzForAnExactRelease() throws Exception {
        ResolvedArtifact artifact = new MariaDbProvider(FakeTransport.client(transport()))
                .resolve(ArtifactRequest.of("mariadb").withVersion("11.8.9").withSystem(LINUX));
        assertEquals("mariadb-11.8.9-linux-systemd-x86_64.tar.gz", artifact.fileName());
        assertTrue(artifact.url().toString().startsWith("https://"));
    }

    @Test
    void newestLtsSeriesWithoutVersion() throws Exception {
        ResolvedArtifact artifact = new MariaDbProvider(FakeTransport.client(new FakeTransport()
                .okResource(API, "mariadb-series.json")
                .okResource(API + "12.3/latest/", "mariadb-11.8-latest.json")))
                .resolve(ArtifactRequest.of("mariadb").withSystem(WINDOWS));
        // The fixture for 12.3 is the 11.8 answer; the point is that 12.3 (newest stable LTS) was asked.
        assertEquals("11.8.9", artifact.coordinate().version());
    }

    @Test
    void otherPlatformsGetAClearAnswer() {
        for (TargetPlatform platform : new TargetPlatform[] {
            new TargetPlatform(Os.LINUX, Arch.AARCH64, Libc.GLIBC),
            new TargetPlatform(Os.LINUX, Arch.X64, Libc.MUSL),
            new TargetPlatform(Os.MAC, Arch.AARCH64, Libc.NONE),
        }) {
            ArtifactNotAvailableException e = assertThrows(ArtifactNotAvailableException.class,
                    () -> new MariaDbProvider(FakeTransport.client(new FakeTransport()))
                            .resolve(ArtifactRequest.of("mariadb").withSystem(platform)));
            assertTrue(e.getMessage().contains("not available for " + platform), e.getMessage());
            assertTrue(e.getMessage().contains("Windows x64 and Linux x86_64 with glibc"), e.getMessage());
        }
    }

    @Test
    void rewritesHttpAndPinsTheMirror() throws Exception {
        assertEquals(URI.create("https://downloads.mariadb.org/rest-api/mariadb/1.2.3/f.zip?mirror=archive"),
                MariaDbProvider.archiveMirror(URI.create("http://downloads.mariadb.org/rest-api/mariadb/1.2.3/f.zip")));
        assertThrows(Exception.class, () -> MariaDbProvider.archiveMirror(URI.create("http://evil.example.net/f.zip")));
    }
}
