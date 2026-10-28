package net.vectora.cloud.download.jre;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.nio.charset.StandardCharsets;

/**
 * Fuzz targets for the files and outputs the JRE management reads: the {@code release} file of a
 * Java home, the output of {@code java -XshowSettings:properties -version} and the install marker.
 * Whatever they contain, the parsers return a value and never throw. In a normal test run they
 * execute in regression mode (stored inputs only).
 */
class JreFilesFuzzTest {

    @FuzzTest
    void releaseFile(byte @NotNull [] input) {
        JavaReleaseFile release = JavaReleaseFile.parse(new String(input, StandardCharsets.UTF_8));
        release.feature();
        release.osArch();
    }

    @FuzzTest
    void settings(byte @NotNull [] input) {
        JavaSettings settings = JavaSettings.parse(new String(input, StandardCharsets.UTF_8));
        settings.feature();
        settings.home();
        settings.arch();
    }

    @FuzzTest
    void marker(byte @NotNull [] input) {
        JreInstaller.Marker.parse(new String(input, StandardCharsets.ISO_8859_1));
    }
}
