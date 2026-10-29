package net.vectora.cloud.database.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.vectora.cloud.download.provider.TargetPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link DownloadingMariaDbBinaries#installed}: the folder of an earlier install, for the backup's dump tool. */
class InstalledMariaDbTest {

    private static final TargetPlatform WINDOWS =
            new TargetPlatform(TargetPlatform.Os.WINDOWS, TargetPlatform.Arch.X64, TargetPlatform.Libc.NONE);

    @Test
    void findsTheRecordedFolderAndNothingElse(@TempDir Path home) throws Exception {
        assertEquals(Optional.empty(), DownloadingMariaDbBinaries.installed(home, WINDOWS), "nothing installed");

        Path base = home.resolve("mariadb-11.8.9-winx64");
        Files.createDirectories(base.resolve("bin"));
        Files.writeString(base.resolve("bin").resolve("mariadbd.exe"), "x");
        Files.writeString(home.resolve(DownloadingMariaDbBinaries.MARKER),
                "folder=mariadb-11.8.9-winx64\nversion=11.8.9\n");
        assertEquals(Optional.of(base), DownloadingMariaDbBinaries.installed(home, WINDOWS));

        // A marker that points out of the home is ignored, and so is a platform without managed MariaDB.
        Files.writeString(home.resolve(DownloadingMariaDbBinaries.MARKER), "folder=../elsewhere\nversion=11.8.9\n");
        assertEquals(Optional.empty(), DownloadingMariaDbBinaries.installed(home, WINDOWS));
        Files.writeString(home.resolve(DownloadingMariaDbBinaries.MARKER),
                "folder=mariadb-11.8.9-winx64\nversion=11.8.9\n");
        TargetPlatform mac = new TargetPlatform(TargetPlatform.Os.MAC, TargetPlatform.Arch.AARCH64, TargetPlatform.Libc.NONE);
        assertEquals(Optional.empty(), DownloadingMariaDbBinaries.installed(home, mac));
    }
}
