package net.vectolyn.cloud.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Step 1 of the start order: the Java version check in the release-8 bootstrap class. */
class BootstrapTest {

    private static final int JAVA_8_MAJOR = 52;

    @ParameterizedTest
    @CsvSource({"1.8,8", "11,11", "17,17", "20,20", "21,21", "25,25", "'',0", "abc,0", "1.7.0,7"})
    void featureVersion(String specificationVersion, int expected) {
        assertEquals(expected, Bootstrap.featureVersion(specificationVersion));
    }

    @Test
    void nullVersionIsUnknown() {
        assertEquals(0, Bootstrap.featureVersion(null));
    }

    @Test
    void languageFromArgumentOtherwiseEnglish() {
        assertEquals("de", Bootstrap.language(new String[] {"--lang", "de"}));
        assertEquals("de", Bootstrap.language(new String[] {"--lang=DE"}));
        assertEquals("en", Bootstrap.language(new String[] {"--lang", "fr"}));
        assertEquals("en", Bootstrap.language(new String[0]));
    }

    @Test
    void messagesNameTheMinimumAndTheFoundVersion() {
        String en = Bootstrap.tooOldMessage(17, "/usr/lib/jvm/java-17", "en");
        assertTrue(en.contains("needs Java 21 or newer"), en);
        assertTrue(en.contains("Java 17 (/usr/lib/jvm/java-17)"), en);
        String de = Bootstrap.tooOldMessage(8, null, "de");
        assertTrue(de.contains("braucht Java 21 oder neuer"), de);
        assertTrue(de.contains("Java 8 (?)"), de);
    }

    @Test
    void exitCodesAgree() {
        assertEquals(Launcher.EXIT_JAVA_TOO_OLD, Bootstrap.EXIT_JAVA_TOO_OLD);
    }

    @Test
    void bootstrapIsCompiledForJava8() throws IOException {
        // Reads the class file without loading it. Must be loadable by an old JVM to show the message.
        try (InputStream raw = getClass().getClassLoader().getResourceAsStream("net/vectolyn/cloud/launcher/Bootstrap.class")) {
            assertNotNull(raw);
            DataInputStream in = new DataInputStream(raw);
            assertEquals(0xCAFEBABE, in.readInt(), "not a class file");
            in.readUnsignedShort();
            assertEquals(JAVA_8_MAJOR, in.readUnsignedShort(), "class file major version");
        }
    }
}
