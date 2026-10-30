package net.vectolyn.cloud.download.jre;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.vectolyn.cloud.download.archive.TarBuilder;
import net.vectolyn.cloud.download.hash.Checksum;
import net.vectolyn.cloud.download.hash.HashAlgorithm;
import net.vectolyn.cloud.download.hash.MultiDigest;

/** Small JRE archives in the layouts Adoptium uses, for tests. */
final class JreArchives {

    private JreArchives() {
    }

    static String release(String version) {
        return "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"" + version + "\"\nOS_ARCH=\"x86_64\"\nIMAGE_TYPE=\"JRE\"\n";
    }

    /** Linux layout: {@code jdk-<v>-jre/bin/java} with mode 0755. */
    static byte[] linuxTarGz(String version, int javaMode) {
        String top = "jdk-" + version + "+9-jre/";
        return new TarBuilder()
                .directory(top)
                .directory(top + "bin/")
                .file(top + "bin/java", "#!/bin/sh\necho fake java\n".getBytes(StandardCharsets.UTF_8), javaMode)
                .file(top + "release", release(version).getBytes(StandardCharsets.UTF_8), 0644)
                .directory(top + "lib/")
                .file(top + "lib/modules", new byte[] {1, 2, 3}, 0644)
                .buildGzip();
    }

    /** macOS layout: {@code jdk-<v>-jre/Contents/Home/bin/java}. */
    static byte[] macTarGz(String version) {
        String home = "jdk-" + version + "+8-jre/Contents/Home/";
        return new TarBuilder()
                .file(home + "bin/java", "fake".getBytes(StandardCharsets.UTF_8), 0755)
                .file(home + "release", release(version).getBytes(StandardCharsets.UTF_8), 0644)
                .file("jdk-" + version + "+8-jre/Contents/Info.plist", "<plist/>".getBytes(StandardCharsets.UTF_8), 0644)
                .buildGzip();
    }

    /** Windows layout: {@code jdk-<v>-jre/bin/java.exe} in a zip. */
    static byte[] windowsZip(String version) {
        String top = "jdk-" + version + "+8-jre/";
        return zip(List.of(top + "bin/java.exe", top + "release"), List.of("MZ fake", release(version)));
    }

    static byte[] zip(List<String> names, List<String> contents) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < names.size(); i++) {
                zip.putNextEntry(new ZipEntry(names.get(i)));
                zip.write(contents.get(i).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static Checksum sha256(byte[] data) {
        return MultiDigest.of(data, List.of()).get(HashAlgorithm.SHA256);
    }
}
