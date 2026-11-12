// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

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
        try (InputStream raw = getClass().getClassLoader().getResourceAsStream("net/vectolyn/cloud/privacy/IpBanStore.class")) {
            assertNotNull(raw, "IpBanStore.class must be on the classpath");
            DataInputStream in = new DataInputStream(raw);
            assertEquals(0xCAFEBABE, in.readInt(), "not a class file");
            in.readUnsignedShort(); // minor version
            assertEquals(JAVA_21_MAJOR, in.readUnsignedShort(), "class file major version");
        }
    }
}
