package net.vectolyn.cloud.download.jre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** The two places a Java version is read from: the {@code release} file and {@code -XshowSettings}. */
class JavaVersionFilesTest {

    // Shortened copy of the release file in OpenJDK21U-jre_x64_linux_hotspot_21.0.13_9.tar.gz.
    static final String TEMURIN_21_RELEASE = """
            IMPLEMENTOR="Eclipse Adoptium"
            IMPLEMENTOR_VERSION="Temurin-21.0.13+9"
            JAVA_RUNTIME_VERSION="21.0.13+9-LTS"
            JAVA_VERSION="21.0.13"
            JAVA_VERSION_DATE="2026-10-20"
            LIBC="gnu"
            MODULES="java.base java.compiler java.datatransfer"
            OS_ARCH="x86_64"
            OS_NAME="Linux"
            SOURCE=""
            IMAGE_TYPE="JRE"
            """;

    // Shortened output of "java -XshowSettings:properties -version" on Temurin 25 for Windows.
    static final String SETTINGS_25 = """
            Property settings:
                file.encoding = UTF-8
                java.class.path =\s
                java.home = C:\\Program Files\\Eclipse Adoptium\\jre-25.0.5.8-hotspot
                java.library.path = C:\\Program Files\\Eclipse Adoptium\\jre-25.0.5.8-hotspot\\bin
                    C:\\WINDOWS\\Sun\\Java\\bin
                java.specification.version = 25
                java.vendor = Eclipse Adoptium
                java.version = 25.0.5
                os.arch = amd64
                os.name = Windows 11

            openjdk version "25.0.5" 2026-10-20 LTS
            OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS)
            """;

    @Test
    void readsTheReleaseFile() {
        JavaReleaseFile release = JavaReleaseFile.parse(TEMURIN_21_RELEASE);
        assertEquals(Optional.of("21.0.13"), release.javaVersion());
        assertEquals(Optional.of("x86_64"), release.osArch());
        assertEquals(OptionalInt.of(21), release.feature());
    }

    @Test
    void readsJava8AndWindowsLineEnds() {
        JavaReleaseFile release = JavaReleaseFile.parse("JAVA_VERSION=\"1.8.0_472\"\r\nOS_ARCH=\"amd64\"\r\n");
        assertEquals(OptionalInt.of(8), release.feature());
        assertEquals(Optional.of("amd64"), release.osArch());
    }

    @Test
    void featureVersions() {
        assertEquals(OptionalInt.of(21), JavaReleaseFile.featureOf("21.0.13"));
        assertEquals(OptionalInt.of(25), JavaReleaseFile.featureOf("25"));
        assertEquals(OptionalInt.of(8), JavaReleaseFile.featureOf("1.8.0_472"));
        assertEquals(OptionalInt.of(8), JavaReleaseFile.featureOf("1.8"));
        assertEquals(OptionalInt.of(17), JavaReleaseFile.featureOf("17.0.17+10"));
        assertEquals(OptionalInt.of(26), JavaReleaseFile.featureOf("26-ea"));
        assertEquals(OptionalInt.of(11), JavaReleaseFile.featureOf("11"));
        for (String bad : new String[] {null, "", "abc", "0", "021", "123", "-1", "21x"}) {
            assertTrue(JavaReleaseFile.featureOf(bad).isEmpty(), String.valueOf(bad));
        }
    }

    @Test
    void ignoresGarbageAndControlCharacters() {
        JavaReleaseFile release = JavaReleaseFile.parse("garbage\nJAVA_VERSION=\"21\u0000\"\nlower=1\n=x\n");
        assertTrue(release.javaVersion().isEmpty());
        assertTrue(release.feature().isEmpty());
    }

    @Test
    void readsTheSettingsOutput() {
        JavaSettings settings = JavaSettings.parse(SETTINGS_25);
        assertEquals(OptionalInt.of(25), settings.feature());
        assertEquals(Optional.of("C:\\Program Files\\Eclipse Adoptium\\jre-25.0.5.8-hotspot"), settings.home());
        assertEquals(Optional.of("25.0.5"), settings.version());
        assertEquals(Optional.of("amd64"), settings.arch());
    }

    @Test
    void settingsOfJava8UseTheSpecificationVersion() {
        JavaSettings settings = JavaSettings.parse("    java.specification.version = 1.8\n    java.version = 1.8.0_472\n");
        assertEquals(OptionalInt.of(8), settings.feature());
    }

    @Test
    void settingsWithoutVersionHaveNoFeature() {
        JavaSettings settings = JavaSettings.parse("Error: Could not create the Java Virtual Machine.\n");
        assertTrue(settings.feature().isEmpty());
        assertTrue(settings.home().isEmpty());
    }
}
