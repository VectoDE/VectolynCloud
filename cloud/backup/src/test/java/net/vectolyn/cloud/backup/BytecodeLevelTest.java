package net.vectolyn.cloud.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** Guards the Java policy in docs/java-compatibility.md: compiled classes target Java 21. */
class BytecodeLevelTest {

    private static final int JAVA_21_MAJOR = 65;

    @Test
    void mainClassIsCompiledForJava21() throws IOException {
        // Reads the class file without loading it, so this also runs on a Java 21 runtime.
        try (InputStream raw = getClass().getClassLoader()
                .getResourceAsStream("net/vectolyn/cloud/backup/BackupService.class")) {
            assertNotNull(raw, "net/vectolyn/cloud/backup/BackupService.class must be on the classpath");
            DataInputStream in = new DataInputStream(raw);
            assertEquals(0xCAFEBABE, in.readInt(), "not a class file");
            in.readUnsignedShort(); // minor version
            assertEquals(JAVA_21_MAJOR, in.readUnsignedShort(), "class file major version");
        }
    }
}
